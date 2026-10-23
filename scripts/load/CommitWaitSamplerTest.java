import java.util.*;
class CommitWaitSamplerTest {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    check(CommitWaitSampler.kind("INSERT INTO `signature_nonces` (key_id,nonce) VALUES (...)",null).equals("NONCE"),"Autocommit nonce must be identifiable while its INSERT is active");
    check(CommitWaitSampler.kind("COMMIT","SELECT `requests` FROM `api_rate_limits` WHERE key_id=?").equals("QUOTA_COMMIT"),"Only a current COMMIT may be associated with the preceding quota statement");
    check(CommitWaitSampler.kind("INSERT INTO `signature_nonces` VALUES (...)","SELECT requests FROM api_rate_limits").equals("NONCE"),"A prior connection user must not misclassify a later nonce");
    check(CommitWaitSampler.kind("COMMIT",null).equals("COMMIT_OTHER"),"Missing history must not invent an API stage");
    check(CommitWaitSampler.kind(null,"SELECT requests FROM api_rate_limits").equals("OTHER"),"Missing current statement is not a quota commit");
    var raw=new LinkedHashMap<String,Object>();raw.put("digest","INSERT INTO signature_nonces VALUES (sensitive)");raw.put("previousDigest","SELECT requests FROM api_rate_limits");raw.put("waitEvent","wait/io/file/sql/binlog");
    var safe=CommitWaitSampler.sanitize(raw);
    check(!safe.containsKey("digest")&&!safe.containsKey("previousDigest")&&!LocalLoad.json(safe).contains("sensitive"),"Never persist SQL text or literals");
    check(safe.get("statementKind").equals("NONCE"),"Keep only bounded classifications and numeric/event fields");
    var commit=new LinkedHashMap<String,Object>();commit.put("digest","COMMIT");commit.put("previousDigest","SELECT requests FROM api_rate_limits");commit.put("transactionId",10L);commit.put("previousTransactionId",9L);commit.put("transactionParentType","TRANSACTION");
    check(CommitWaitSampler.sanitize(commit).get("statementKind").equals("COMMIT_OTHER"),"Connection reuse must not associate a previous transaction's quota query");
    commit.put("previousTransactionId",10L);
    check(CommitWaitSampler.sanitize(commit).get("statementKind").equals("QUOTA_COMMIT"),"Matching current transaction history can identify quota commits");
    var consumers=new ArrayList<Map<String,Object>>();for(var name:List.of("global_instrumentation","thread_instrumentation","events_stages_current","events_waits_current","events_statements_current","events_statements_history","statements_digest"))consumers.add(new HashMap<>(Map.of("NAME",name,"ENABLED","YES")));
    CommitWaitSampler.requireConsumers(consumers);
    consumers.getLast().put("ENABLED","NO");
    try{CommitWaitSampler.requireConsumers(consumers);throw new AssertionError("Disabled digest consumer accepted");}catch(IllegalStateException expected){}
    System.out.println("Commit wait classification and sanitization tests passed");
  }
}
