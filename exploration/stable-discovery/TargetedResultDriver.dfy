// A bounded response driver over the history-free reducer. The driver stops
// before the scalar discovered count can exceed a requested target and buffers
// exactly the newly observed result suffix, not the delivered history.
// Exploratory proof model; intentionally excluded from release artifacts.
include "HistoryFreeReducer.dfy"

module TargetedResultDriver {
  import R = StableReducer
  import H = HistoryFreeReducer

  datatype DriverState = DriverState(
    runtime: H.RuntimeState,
    buffered: seq<nat>
  )

  predicate Refines(
    specification: R.State,
    start: nat,
    driver: DriverState
  ) {
    H.Refines(specification, driver.runtime) &&
    start <= |specification.results| &&
    driver.buffered == specification.results[start..]
  }

  function Initial(runtime: H.RuntimeState): DriverState {
    DriverState(runtime, [])
  }

  function Step(program: R.Program, driver: DriverState): DriverState {
    var transition := H.RuntimeStep(program, driver.runtime);
    DriverState(
      transition.state,
      if transition.output.Output?
      then driver.buffered + [transition.output.value]
      else driver.buffered
    )
  }

  function SpecificationRunToTarget(
    program: R.Program,
    specification: R.State,
    target: nat,
    fuel: nat
  ): R.State
    decreases fuel
  {
    if fuel == 0 ||
       |specification.stack| == 0 ||
       |specification.results| >= target
    then specification
    else SpecificationRunToTarget(
           program,
           R.Step(program, specification),
           target,
           fuel - 1
         )
  }

  function RunToTarget(
    program: R.Program,
    driver: DriverState,
    target: nat,
    fuel: nat
  ): DriverState
    decreases fuel
  {
    if fuel == 0 ||
       |driver.runtime.stack| == 0 ||
       driver.runtime.discovered >= target
    then driver
    else RunToTarget(
           program,
           Step(program, driver),
           target,
           fuel - 1
         )
  }

  function GreaterThanStopMutant(discovered: nat, target: nat): nat {
    if discovered > target then discovered else discovered + 1
  }

  lemma GreaterThanStopMutantPassesTarget(target: nat)
    ensures GreaterThanStopMutant(target, target) == target + 1
    ensures GreaterThanStopMutant(target, target) > target
  {
  }

  lemma InitialRefines(
    specification: R.State,
    runtime: H.RuntimeState
  )
    requires H.Refines(specification, runtime)
    ensures Refines(
              specification,
              runtime.discovered,
              Initial(runtime)
            )
  {
    H.RuntimeDiscoveredCountMatchesObservation(specification, runtime);
  }

  lemma StepRefines(
    program: R.Program,
    specification: R.State,
    start: nat,
    driver: DriverState
  )
    requires Refines(specification, start, driver)
    ensures Refines(
              R.Step(program, specification),
              start,
              Step(program, driver)
            )
  {
    H.RuntimeStepRefinesSpecification(
      program, specification, driver.runtime
    );
    if |specification.stack| > 0 &&
       specification.stack[0] in program.resultNodes {
      assert R.Step(program, specification).results ==
             specification.results + [specification.stack[0]];
      assert H.RuntimeStep(program, driver.runtime).output ==
             H.Output(specification.stack[0]);
      assert (specification.results +
              [specification.stack[0]])[start..] ==
             specification.results[start..] +
             [specification.stack[0]];
    } else {
      assert R.Step(program, specification).results ==
             specification.results;
      assert H.RuntimeStep(program, driver.runtime).output == H.NoOutput;
    }
  }

  lemma OneStepDoesNotPassTarget(
    program: R.Program,
    driver: DriverState,
    target: nat
  )
    requires driver.runtime.discovered < target
    ensures Step(program, driver).runtime.discovered <= target
  {
  }

  lemma RunToTargetRefines(
    program: R.Program,
    specification: R.State,
    start: nat,
    driver: DriverState,
    target: nat,
    fuel: nat
  )
    requires Refines(specification, start, driver)
    ensures Refines(
              SpecificationRunToTarget(
                program, specification, target, fuel
              ),
              start,
              RunToTarget(program, driver, target, fuel)
            )
    decreases fuel
  {
    if fuel > 0 &&
       |specification.stack| > 0 &&
       |specification.results| < target {
      assert |driver.runtime.stack| > 0;
      assert driver.runtime.discovered < target;
      StepRefines(program, specification, start, driver);
      RunToTargetRefines(
        program,
        R.Step(program, specification),
        start,
        Step(program, driver),
        target,
        fuel - 1
      );
    }
  }

  lemma RunToTargetDoesNotPassTarget(
    program: R.Program,
    driver: DriverState,
    target: nat,
    fuel: nat
  )
    requires driver.runtime.discovered <= target
    ensures RunToTarget(
              program, driver, target, fuel
            ).runtime.discovered <= target
    decreases fuel
  {
    if fuel > 0 &&
       |driver.runtime.stack| > 0 &&
       driver.runtime.discovered < target {
      OneStepDoesNotPassTarget(program, driver, target);
      RunToTargetDoesNotPassTarget(
        program,
        Step(program, driver),
        target,
        fuel - 1
      );
    }
  }

  lemma FreshRunBufferEqualsDiscoveredDelta(
    program: R.Program,
    specification: R.State,
    runtime: H.RuntimeState,
    target: nat,
    fuel: nat
  )
    requires H.Refines(specification, runtime)
    requires runtime.discovered <= target
    ensures var completed := RunToTarget(
                               program,
                               Initial(runtime),
                               target,
                               fuel
                             );
            |completed.buffered| ==
              completed.runtime.discovered - runtime.discovered
    ensures var completed := RunToTarget(
                               program,
                               Initial(runtime),
                               target,
                               fuel
                             );
            |completed.buffered| <= target - runtime.discovered
  {
    InitialRefines(specification, runtime);
    RunToTargetRefines(
      program,
      specification,
      runtime.discovered,
      Initial(runtime),
      target,
      fuel
    );
    RunToTargetDoesNotPassTarget(
      program, Initial(runtime), target, fuel
    );
    var completedSpecification := SpecificationRunToTarget(
                                    program,
                                    specification,
                                    target,
                                    fuel
                                  );
    var completed := RunToTarget(
                       program, Initial(runtime), target, fuel
                     );
    assert H.Refines(completedSpecification, completed.runtime);
    H.RuntimeDiscoveredCountMatchesObservation(
      completedSpecification, completed.runtime
    );
    assert completed.buffered ==
           completedSpecification.results[runtime.discovered..];
    assert |completed.buffered| ==
           |completedSpecification.results| - runtime.discovered;
  }

  lemma ReachedTargetBufferIsExactSuffix(
    program: R.Program,
    specification: R.State,
    runtime: H.RuntimeState,
    target: nat,
    fuel: nat
  )
    requires H.Refines(specification, runtime)
    requires runtime.discovered <= target
    requires RunToTarget(
               program, Initial(runtime), target, fuel
             ).runtime.discovered == target
    ensures runtime.discovered <=
              |SpecificationRunToTarget(
                 program, specification, target, fuel
               ).results|
    ensures var completedSpecification := SpecificationRunToTarget(
                                           program,
                                           specification,
                                           target,
                                           fuel
                                         );
            RunToTarget(
              program, Initial(runtime), target, fuel
            ).buffered ==
              completedSpecification.results[runtime.discovered..]
  {
    InitialRefines(specification, runtime);
    RunToTargetRefines(
      program,
      specification,
      runtime.discovered,
      Initial(runtime),
      target,
      fuel
    );
    var completedSpecification := SpecificationRunToTarget(
                                    program,
                                    specification,
                                    target,
                                    fuel
                                  );
    var completed := RunToTarget(
                       program, Initial(runtime), target, fuel
                     );
    H.RuntimeDiscoveredCountMatchesObservation(
      completedSpecification, completed.runtime
    );
    assert |completedSpecification.results| == target;
  }
}
