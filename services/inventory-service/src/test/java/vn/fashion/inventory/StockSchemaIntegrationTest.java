package vn.fashion.inventory;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

class StockSchemaIntegrationTest extends InventoryTestSupport {
  @Test void reservedCannotExceedOnHand() {
    assertThatThrownBy(() -> seedStock("A",1,2)).isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void onHandCannotBeNegative() {
    assertThatThrownBy(() -> seedStock("A",-1,0)).isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void variantIdIsUnique() {
    seedStock("A",0,0);
    assertThatThrownBy(() -> jdbc.update("insert into stock_items(sku,product_id,variant_id) select 'B',product_id,variant_id from stock_items where sku='A'"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void ledgerIsAppendOnlyForRuntime() {
    seedStock("A",0,0); ledger("op","ADJUST");
    for (String action : new String[]{"update stock_transactions set reason='changed'", "delete from stock_transactions"})
      assertThatThrownBy(() -> jdbc.update(action)).isInstanceOf(DataAccessException.class)
          .rootCause().hasMessageContaining("permission denied");
    assertThat(jdbc.queryForObject("select count(*) from stock_transactions",Integer.class)).isEqualTo(1);
  }
  @Test void operationKeyUniquePerSku() {
    seedStock("A",0,0); ledger("op","ADJUST");
    assertThatThrownBy(() -> ledger("op","ADJUST")).isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void ledgerTypeIsConstrained() {
    seedStock("A",0,0);
    assertThatThrownBy(() -> ledger("op","FOO")).isInstanceOf(DataIntegrityViolationException.class);
  }
  @Test void readinessIsUp() {
    assertThat(json(send("GET","/actuator/health/readiness",null,null,null)).path("status").asText()).isEqualTo("UP");
  }
  void ledger(String key,String type) {
    jdbc.update("insert into stock_transactions(id,sku,operation_key,type,on_hand_delta,reserved_delta,reason) values (?,'A',?,?,1,0,'test')",UUID.randomUUID(),key,type);
  }
}
