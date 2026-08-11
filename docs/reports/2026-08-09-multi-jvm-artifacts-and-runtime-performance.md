# Multi-JVM artifacts and EACL runtime performance

Date: 2026-08-09

This report is a follow-up to the completed
[`publish-modular-clojars-artifacts`](../../openspec/changes/publish-modular-clojars-artifacts/design.md)
OpenSpec change. It records an architectural finding; it does not change that
change's current contract that the ordinary Clojars build targets Java 26.

## Summary

EACL does not need a differently named Clojars artifact for every JVM. The
simplest portable publication is one JAR compiled for the oldest supported JVM;
the same JAR runs on that release and newer JVMs. A newer HotSpot runtime still
applies its own JIT, garbage collector, intrinsics, and runtime optimizations to
older-compatible bytecode.

A multi-release JAR can also keep one Maven coordinate while supplying selected
release-specific implementations under `META-INF/versions/<release>`. That is
valuable only when an implementation actually uses newer Java APIs or a
different algorithm. Recompiling the same Dafny-generated sources at several
class-file levels would add artifact size and a larger verification matrix
without an expected runtime gain.

The current EACL kernel uses Java 8-era APIs and successfully compiles with
`javac --release 8`. Its performance-sensitive characteristics are instead a
high allocation rate, many small immutable objects, persistent collection
updates, hundreds of generated classes, and repeated conversions through
`BigInteger` and Dafny sequences. Consequently, newer GC, object-layout, JIT,
and warmup improvements matter more than newer Java language features.

The provisional recommendation is:

- regard Java 17 as the oldest sensible common production-performance floor;
- prefer Java 25 LTS for production deployments;
- build with and test the latest Java 26 runtime;
- publish one lower-baseline JAR if ordinary Clojars consumption must work on
  older JVMs, rather than publishing per-JVM artifact names; and
- retain explicit custom source targets where a backend's dependencies permit
  an older JVM.

This recommendation requires a follow-up OpenSpec change before it replaces the
current Java 26 Clojars target.

## Publication choices

### One baseline JAR

For example, `dev.eacl/eacl` can be built with `--release 17`. The same artifact
then runs on Java 17, 21, 25, and 26. The backend artifacts continue to depend
on the exact matching core version and need no coordinate changes.

This is how Clojure separates bytecode compatibility from its preferred
runtime: current Clojure releases use Java 8-compatible bytecode while
recommending Java 25. See the
[Clojure Java compatibility guidance](https://clojure.org/releases/downloads).

### Multi-release JAR

A multi-release EACL JAR could contain a baseline implementation at the JAR
root and a genuinely newer implementation under, for example,
`META-INF/versions/25`. The JVM automatically selects the highest implementation
not newer than itself. Java 8 ignores the versioned tree and sees the root.
This remains one Maven version and one Clojars coordinate. See
[JEP 238](https://openjdk.org/jeps/238) and the
[`jar --release` documentation](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jar.html).

There is presently no identified EACL implementation that justifies an
overlay. If a future Java-specific helper uses a stable new API, only that
narrow helper should be versioned. Duplicating the tightly coupled generated
kernel/runtime closure would make release verification and classpath inspection
substantially more complex.

### Separate classifiers or artifact names

Classifiers such as `jdk17` and `jdk26`, or distinct artifact names, require the
consumer to choose a variant. Maven and tools.deps do not content-negotiate an
ordinary JAR from the current JVM version. This is appropriate only if the
variants have meaningfully different dependencies or public behavior and is
not recommended for EACL's current kernel.

## EACL-specific runtime findings

The generated production boundary creates `BigInteger` values and Dafny
sequences for EIDs, counters, limits, and scan payloads. EACL's patched Dafny
maps and sets use Clojure persistent hash collections. These representations
create many short-lived tuple, sequence, wrapper, and collection-node objects.
The released core also carries hundreds of generated classes, which affects
startup and warmup.

The JVM improvements most likely to matter are:

| Runtime | Potential EACL benefit |
| --- | --- |
| Java 9-11 | Compact Strings reduce identifier footprint and GC pressure; G1 is the default collector. |
| Java 17 | Mature G1 improvements, production low-pause collectors, elastic metaspace, and first-class macOS/AArch64 support provide a modern LTS baseline. |
| Java 21 | Generational ZGC better matches EACL's short-lived allocation profile. Virtual threads can improve host-application concurrency around blocking Datomic/storage operations, but do not accelerate CPU-bound traversal. |
| Java 24 | Virtual-thread monitor pinning is largely removed. Application-trained AOT class loading can reduce startup work. |
| Java 25 | Opt-in compact object headers can improve footprint and locality for EACL's many small objects. AOT method profiles can reduce warmup. |
| Java 26 | G1 reduces write-barrier synchronization and may improve default-G1 throughput without an EACL source change. AOT object caching works with every collector. |

Relevant primary sources include
[Compact Strings](https://openjdk.org/jeps/254),
[JDK 11-to-17 changes](https://openjdk.org/projects/jdk/17/jeps-since-jdk-11),
[Generational ZGC](https://openjdk.org/jeps/439),
[virtual threads](https://openjdk.org/jeps/444),
[unpinning synchronized virtual threads](https://openjdk.org/jeps/491),
[AOT class loading and linking](https://openjdk.org/jeps/483),
[compact object headers](https://openjdk.org/jeps/519), and
[the Java 26 G1 synchronization improvement](https://openjdk.org/jeps/522).

The likely gains are runtime gains. They do not require Java 25- or
Java 26-versioned `CacheKernel.class` files. Features such as records, pattern
matching, sequenced collections, and the Vector API have no identified direct
benefit to the current generated kernel. AOT caches are trained against an
application and exact classpath, so EACL cannot publish one generic AOT cache
through Clojars.

## Practical floor

Java 17 is the provisional oldest common performance and ecosystem floor. The
current pinned Datomic Peer `1.0.7622` can run on Java 11, but Datomic announced
that releases after 2026-05-30 require Java 17 or later. See the
[Datomic release notice](https://docs.datomic.com/release-notices.html). Java 17
therefore avoids choosing a baseline that the primary backend has already begun
to retire.

Java 25 is the preferred production runtime because it is an LTS release,
Clojure recommends it, and its optional compact headers directly address the
kernel's small-object-heavy representation. Java 26 remains valuable as the
latest-runtime test and for its G1 improvements, even if the artifact bytecode
targets Java 17.

## Required benchmark before changing policy

The existing reference benchmark was recorded only on Java 24, so release notes
and code inspection cannot establish the exact performance cutoff. Run the same
candidate JAR, compiled once for the proposed baseline, on Java 17, 21, 25, and
26 on the same hardware. The matrix should include:

- the DataScript recursive traversal workload to isolate kernel CPU and
  allocation behavior;
- the Datomic multipath full walk and permission check paths;
- cold startup and warmup;
- hot completed-cache and continuation-cache hits; and
- default G1 on every JVM, Generational ZGC where available, and Java 25 compact
  headers both disabled and enabled.

Record throughput, p50/p95/p99 latency, allocated bytes per operation, live and
peak heap, GC CPU/pause time, RSS, startup, and warmup. Define "good" before the
run, for example as the oldest JVM within 10% of Java 25 steady-state throughput
while meeting the project's p99 and memory constraints. The current benchmark
methodology is documented in
[`v6-vs-v8.0.md`](../benchmarks/v6-vs-v8.0.md).
