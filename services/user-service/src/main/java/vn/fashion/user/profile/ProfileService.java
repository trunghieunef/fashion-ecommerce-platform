package vn.fashion.user.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.fashion.user.web.Api;

/**
 * Profile and address book (06 section 2.1). Every address mutation locks the user row first, so
 * concurrent requests cannot leave zero or two defaults; the partial unique index backs this up.
 */
@Service
public class ProfileService {
  public record Profile(UUID id, String email, String fullName, String locale, boolean emailVerified) {
  }

  public record Address(UUID id, String recipientName, String phone, String provinceCode, String districtCode,
                        String wardCode, String addressLine, boolean isDefault) {
  }

  public record AddressInput(String recipientName, String phone, String provinceCode, String districtCode,
                             String wardCode, String addressLine, Boolean isDefault) {
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public ProfileService(JdbcClient jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  public Profile profile(UUID userId) {
    return jdbc.sql("""
            select id, email, full_name, locale, email_verified_at is not null as verified
            from users where id = :id
            """)
        .param("id", userId)
        .query((rs, row) -> new Profile(rs.getObject("id", UUID.class), rs.getString("email"),
            rs.getString("full_name"), rs.getString("locale"), rs.getBoolean("verified")))
        .single();
  }

  /** Only name and locale; e-mail, roles and auth_version are never taken from this request. */
  public Profile update(UUID userId, String fullName, String locale) {
    jdbc.sql("update users set full_name = :name, locale = :locale, updated_at = now() where id = :id")
        .param("name", fullName).param("locale", locale).param("id", userId).update();
    return profile(userId);
  }

  public List<Address> addresses(UUID userId) {
    return jdbc.sql(SELECT + " where user_id = :user order by is_default desc, created_at desc, id")
        .param("user", userId).query(ProfileService::address).list();
  }

  public Address create(UUID userId, AddressInput input) {
    return tx.execute(status -> {
      lockUser(userId);
      boolean first = jdbc.sql("select count(*) from user_addresses where user_id = :user")
          .param("user", userId).query(Integer.class).single() == 0;
      boolean makeDefault = first || Boolean.TRUE.equals(input.isDefault());
      if (makeDefault) {
        clearDefault(userId);
      }
      UUID id = UUID.randomUUID();
      jdbc.sql("""
              insert into user_addresses(id, user_id, recipient_name, phone, province_code, district_code,
                ward_code, address_line, is_default)
              values (:id, :user, :name, :phone, :province, :district, :ward, :line, :isDefault)
              """)
          .param("id", id).param("user", userId).param("name", input.recipientName())
          .param("phone", input.phone()).param("province", input.provinceCode())
          .param("district", input.districtCode()).param("ward", input.wardCode())
          .param("line", input.addressLine()).param("isDefault", makeDefault)
          .update();
      return find(userId, id).orElseThrow();
    });
  }

  public Address update(UUID userId, UUID addressId, AddressInput input) {
    return tx.execute(status -> {
      lockUser(userId);
      Address current = find(userId, addressId).orElseThrow(ProfileService::notFound);
      boolean makeDefault = Boolean.TRUE.equals(input.isDefault());
      if (current.isDefault() && Boolean.FALSE.equals(input.isDefault())) {
        throw new Api.Problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "DEFAULT_ADDRESS_REQUIRED",
            List.of(new Api.FieldError("is_default", "set another address as default instead")));
      }
      if (makeDefault && !current.isDefault()) {
        clearDefault(userId);
      }
      jdbc.sql("""
              update user_addresses set recipient_name = :name, phone = :phone, province_code = :province,
                district_code = :district, ward_code = :ward, address_line = :line,
                is_default = :isDefault, updated_at = now()
              where id = :id and user_id = :user
              """)
          .param("name", input.recipientName()).param("phone", input.phone())
          .param("province", input.provinceCode()).param("district", input.districtCode())
          .param("ward", input.wardCode()).param("line", input.addressLine())
          .param("isDefault", current.isDefault() || makeDefault)
          .param("id", addressId).param("user", userId)
          .update();
      return find(userId, addressId).orElseThrow();
    });
  }

  /** Deleting the default promotes the most recently created remaining address. */
  public void delete(UUID userId, UUID addressId) {
    tx.executeWithoutResult(status -> {
      lockUser(userId);
      Address current = find(userId, addressId).orElseThrow(ProfileService::notFound);
      jdbc.sql("delete from user_addresses where id = :id and user_id = :user")
          .param("id", addressId).param("user", userId).update();
      if (current.isDefault()) {
        jdbc.sql("""
                update user_addresses set is_default = true, updated_at = now()
                where id = (select id from user_addresses where user_id = :user
                            order by created_at desc, id limit 1)
                """)
            .param("user", userId).update();
      }
    });
  }

  private static final String SELECT = """
      select id, recipient_name, phone, province_code, district_code, ward_code, address_line, is_default
      from user_addresses""";

  private Optional<Address> find(UUID userId, UUID addressId) {
    return jdbc.sql(SELECT + " where id = :id and user_id = :user")
        .param("id", addressId).param("user", userId).query(ProfileService::address).optional();
  }

  private void lockUser(UUID userId) {
    jdbc.sql("select id from users where id = :id for update").param("id", userId).query(UUID.class).single();
  }

  private void clearDefault(UUID userId) {
    jdbc.sql("update user_addresses set is_default = false, updated_at = now() where user_id = :user and is_default")
        .param("user", userId).update();
  }

  private static Api.Problem notFound() {
    return new Api.Problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "ADDRESS_NOT_FOUND", List.of());
  }

  private static Address address(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Address(rs.getObject("id", UUID.class), rs.getString("recipient_name"), rs.getString("phone"),
        rs.getString("province_code"), rs.getString("district_code"), rs.getString("ward_code"),
        rs.getString("address_line"), rs.getBoolean("is_default"));
  }
}
