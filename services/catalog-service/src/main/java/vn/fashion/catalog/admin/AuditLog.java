package vn.fashion.catalog.admin;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuditLog {
  private final JdbcClient jdbc;
  private final ObjectMapper json;
  public AuditLog(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
  public void record(UUID actor, String action, String type, UUID id, String reason, Object before, Object after) {
    jdbc.sql("""
        insert into audit_logs(id,actor_id,action,resource_type,resource_id,reason,before_data,after_data,request_id)
        values (:id,:actor,:action,:type,:resource,:reason,cast(:before as jsonb),cast(:after as jsonb),:request)
        """).param("id", UUID.randomUUID()).param("actor", actor).param("action", action).param("type", type)
        .param("resource", id.toString()).param("reason", reason)
        .param("before", before == null ? null : json.writeValueAsString(before))
        .param("after", json.writeValueAsString(after)).param("request", UUID.randomUUID()).update();
  }
}
