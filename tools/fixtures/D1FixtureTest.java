import java.time.*;

class D1FixtureTest {
  public static void main(String[] args) throws Exception {
    Class<?> fixture;
    try { fixture = Class.forName("D1Fixture"); }
    catch (ClassNotFoundException missing) { throw new AssertionError("Isolated historical D1 fixture is missing", missing); }
    var guard = fixture.getDeclaredMethod("guardDatabase", String.class);
    guard.setAccessible(true);
    for (String forbidden : new String[]{"jdbc:mysql://127.0.0.1:3307/merchantpay", "jdbc:mysql://db/merchantpay", "jdbc:mysql://db/merchantpay_fixture_abcdef12?allowMultiQueries=true"}) {
      try { guard.invoke(null, forbidden); throw new AssertionError("Unsafe database accepted"); }
      catch (java.lang.reflect.InvocationTargetException rejected) { if (!(rejected.getCause() instanceof IllegalArgumentException)) throw rejected; }
    }
    guard.invoke(null, "jdbc:mysql://127.0.0.1:13308/merchantpay_fixture_abcdef12");
    var amount = fixture.getDeclaredMethod("amount", long.class); amount.setAccessible(true);
    if ((long)amount.invoke(null, 0L) != 1 || (long)amount.invoke(null, 1L) != 1_000_000_000L) throw new AssertionError("Money boundaries missing");
    var businessDay = fixture.getDeclaredMethod("businessDay", long.class, long.class, int.class, LocalDate.class); businessDay.setAccessible(true);
    LocalDate first = LocalDate.of(2026,9,20);
    var days = new java.util.HashSet<LocalDate>();
    for (long i = 0; i < 1000; i++) days.add((LocalDate)businessDay.invoke(null, i, 1000L, 30, first));
    if (days.size() != 30 || !days.contains(first.plusDays(29))) throw new AssertionError("Historical distribution must cover all thirty days");
    var verifyFact=fixture.getDeclaredMethod("verifyAuthorityFact",String[].class,String[].class,String.class,boolean.class);verifyFact.setAccessible(true);
    var chargedFee=fixture.getDeclaredMethod("authorityChargedFee",boolean.class,String.class,String.class,String.class);chargedFee.setAccessible(true);
    if(!"0".equals(chargedFee.invoke(null,true,"CLOSED","payload-hash","60"))||!"0".equals(chargedFee.invoke(null,true,"FAILED","payload-hash","60")))throw new AssertionError("Unsuccessful historical payment quotes are not charged fees");
    if(!"60".equals(chargedFee.invoke(null,true,"SUCCEEDED","payload-hash","60"))||!"18".equals(chargedFee.invoke(null,false,"SUCCEEDED","payload-hash","18"))||!"1".equals(chargedFee.invoke(null,true,"CLOSED","closed","1")))throw new AssertionError("Successful fees, refund fees and exact close tombstones remain strict");
    String[] closed={"p1","merchant-a","order-1","CLOSED","100","0","CNY",null};
    String[] tombstone={"p1","UNASSIGNED","close-before-submit","CLOSED","0","0","CNY","mock-close-1"};
    verifyFact.invoke(null,closed,tombstone,"closed",true);
    String[] paid={"p1","merchant-a","order-1","SUCCEEDED","100","1","CNY","channel-ref"};
    verifyFact.invoke(null,paid,paid.clone(),"request-hash",true);
    for(int column=0;column<8;column++) {
      String[] corrupt=paid.clone();corrupt[column]="different";
      try {verifyFact.invoke(null,paid,corrupt,"request-hash",true);throw new AssertionError("Ordinary authority mismatch accepted in column "+column);}
      catch(java.lang.reflect.InvocationTargetException rejected){if(!(rejected.getCause() instanceof IllegalStateException))throw rejected;}
    }
    for(int mutation=0;mutation<5;mutation++) {
      String[] corrupt=tombstone.clone();String hash="closed";boolean payment=true;
      if(mutation==0)corrupt[0]="another-id";
      if(mutation==1)corrupt[3]="SUCCEEDED";
      if(mutation==2)corrupt[4]="1";
      if(mutation==3)hash="ordinary-request";
      if(mutation==4)payment=false;
      try {verifyFact.invoke(null,closed,corrupt,hash,payment);throw new AssertionError("Corrupt channel close fact accepted");}
      catch(java.lang.reflect.InvocationTargetException rejected){if(!(rejected.getCause() instanceof IllegalStateException))throw rejected;}
    }
    System.out.println("D1 fixture scope and distribution guards passed.");
  }
}
