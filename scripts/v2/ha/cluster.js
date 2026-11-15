// MySQL Shell 8.4 JavaScript. No credentials on command lines. NEVER force quorum/clone.
var mode=os.getenv('V2_HA_MODE')||'status';
var targets=JSON.parse(os.getenv('V2_HA_TARGETS')||'[]');
if(targets.length!==3||new Set(targets).size!==3||targets.some(x=>!/^[-A-Za-z0-9.]+:[0-9]{2,5}$/.test(x)))throw new Error('Three distinct approved host:port targets required');
var user=os.getenv('V2_HA_USER')||'cluster_admin';
var password=shell.prompt('Cluster administrator password: ',{type:'password'});
function options(target){var parts=target.split(':');return {scheme:'mysql',host:parts[0],port:Number(parts[1]),user:user,password:password,sslMode:'VERIFY_IDENTITY'};}
var sessions=targets.map(x=>mysql.getSession(options(x)));
try {
  if(mode==='create'){
    if(os.getenv('V2_HA_CONFIRM')!=='CREATE_NEW_EMPTY_V2_CLUSTER')throw new Error('Creation confirmation missing');
    sessions.forEach(s=>{var tables=s.runSql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema NOT IN ('mysql','information_schema','performance_schema','sys')").fetchOne()[0];if(Number(tables)!==0)throw new Error('Only empty isolated servers may be initialized');});
    targets.forEach(x=>dba.configureInstance(options(x),{interactive:false}));
    shell.connect(options(targets[0]));
    var cluster=dba.createCluster('merchantpayV2',{multiPrimary:false,memberSslMode:'REQUIRED',consistency:'BEFORE_ON_PRIMARY_FAILOVER',exitStateAction:'READ_ONLY'});
    targets.slice(1).forEach(x=>cluster.addInstance(options(x),{recoveryMethod:'incremental',interactive:false}));
    print(JSON.stringify(cluster.status({extended:1})));
  } else {
    shell.connect(options(targets[0]));
    if(mode==='recover'){
      if(os.getenv('V2_HA_CONFIRM')!=='WRITERS_STOPPED_RECOVERY_REVIEWED')throw new Error('Stopped writers and reviewed GTID evidence required');
      // Official Shell chooses the safe GTID superset; no force option or manual bootstrap.
      print(JSON.stringify(dba.rebootClusterFromCompleteOutage('merchantpayV2',{dryRun:os.getenv('V2_HA_APPLY')!=='true',force:false})));
    } else {
      var cluster=dba.getCluster('merchantpayV2');
      if(mode==='switch-primary'){
        var target=os.getenv('V2_HA_PRIMARY');if(!targets.includes(target)||os.getenv('V2_HA_CONFIRM')!=='WRITERS_STOPPED_SWITCH_REVIEWED')throw new Error('Approved target and stopped writers required');
        if(os.getenv('V2_HA_APPLY')==='true')cluster.setPrimaryInstance(target);
      } else if(mode!=='status')throw new Error('Unsupported operation');
      print(JSON.stringify(cluster.status({extended:1})));
    }
  }
} finally {sessions.forEach(s=>s.close());password=null;}
