# Local performance baseline implementation plan

> Execute inline with superpowers:executing-plans; one final independent review. Existing user authorization covers this next step. No Git repository, so no commits/worktrees.

**Goal:** A repeatable, explicitly bounded D0 baseline with ten-minute warmup and three recorded rounds; never reinterpret it as R1/D1 certification.

**Architecture:** Extend existing start/load/diagnosis scripts. Optional Compose override caps MySQL4CPU/4GiB/cache1GiB; native platform4 logical CPUs/1536MiB heap, simulator2/768MiB, load2/512MiB. Windows affinities0-3/4-5/6-7 and JVM CPU views agree. These are caps, not exclusive cores or whole-process native memory limits. Preserve data, double1 durability and business concurrency.

**Files:** infra/compose.performance.yaml; scripts/LocalPerformance.ps1 (shared mode/CPU and qualification guards); existing start-local and load/run-local; load/baseline-local.ps1 (protocol/manifest), RuntimeSnapshot.java (read-only consistent state/config), test-baseline.ps1; docs/CI integration.

- [x] Red then green guards: reject live profile switch, insufficient CPU, short warmup, omitted/failed rounds, drift, missing latency. Retain all rounds and quantify spread.
- [x] Implement optional startup/load resource controls and verify actual Docker/native configuration. Mode switches require stopped project; no volume deletion. Capture process identities, JAR/config hashes, server identity, resource limits and dataset counts.
- [x] Execute normal smoke,600s at30/s warmup and three60s rounds at50/s with diagnostic coverage and queue drains. Record exceptions and failed qualifications. Every interval requires equal fingerprints, clean observations, money invariants and zero final capacity drift. Report throughput and P99 spread; D0 qualification uses ≤10% throughput spread, ≥95% scheduled completed throughput lower bound and ≤1.5x P99 upper-bound spread, not a release SLO.
- [x] Independent read-only review, appropriate offline/real checks, documentation/evidence sync. Leave optional performance mode active and clearly document return to normal via stop/start. No financial/backend changes or full backend rerun needed.

Ruling: Native CPU affinity plus Docker quotas provides repeatable caps while shared Windows/WSL/storage remain unisolated; report that limit explicitly. Keep current mock100% success distribution and single merchant as D0 only. Configuration/diagnostic scripts are the deliverable; defer financial commit batching until this baseline is measured.

Execution: actual protocol baseline-20261019T094822Z-94a0156d completed; qualification false due503inwarmup/all3rounds andP99ratio1.742>1.5. No threshold was relaxed. All26637accepted payments posted/balanced, source/runtime fingerprints held, queues/drift0. Independent review and final offline guards/Java checks passed; final CheckOnly confirmed lock released after failed qualification. Optional performance mode remains active. Qualification failure is a retained measurement result, not a claim that a stable comparison baseline has been achieved.
