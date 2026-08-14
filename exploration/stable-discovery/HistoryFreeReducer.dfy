// Refinement from the observation-rich semantic reducer to the runtime core.
// Production retains no complete result history: only canonical work, exact
// admission, and a scalar discovered-result count.
include "StableReducer.dfy"

module HistoryFreeReducer {
  import R = StableReducer

  datatype MaybeOutput = NoOutput | Output(value: nat)

  datatype RuntimeState = RuntimeState(
    stack: seq<nat>,
    admitted: set<nat>,
    discovered: nat
  )

  datatype Transition = Transition(
    state: RuntimeState,
    output: MaybeOutput
  )

  function Erase(specification: R.State): RuntimeState {
    RuntimeState(
      specification.stack,
      specification.admitted,
      |specification.results|
    )
  }

  predicate Refines(
    specification: R.State,
    runtime: RuntimeState
  ) {
    runtime == Erase(specification)
  }

  function RuntimeStep(
    program: R.Program,
    runtime: RuntimeState
  ): Transition {
    if |runtime.stack| == 0 then
      Transition(runtime, NoOutput)
    else
      var node := runtime.stack[0];
      var admission := R.Admit(
                         R.Successors(program, node),
                         runtime.admitted
                       );
      var next := RuntimeState(
                    admission.newValues + runtime.stack[1..],
                    admission.seen,
                    runtime.discovered +
                      (if node in program.resultNodes then 1 else 0)
                  );
      Transition(
        next,
        if node in program.resultNodes then Output(node) else NoOutput
      )
  }

  lemma RuntimeStepRefinesSpecification(
    program: R.Program,
    specification: R.State,
    runtime: RuntimeState
  )
    requires Refines(specification, runtime)
    ensures Refines(
              R.Step(program, specification),
              RuntimeStep(program, runtime).state
            )
    ensures RuntimeStep(program, runtime).output.Output? <==>
            |specification.stack| > 0 &&
            specification.stack[0] in program.resultNodes
    ensures RuntimeStep(program, runtime).output.Output? ==>
            RuntimeStep(program, runtime).output.value ==
              specification.stack[0]
  {
  }

  function RuntimeRun(
    program: R.Program,
    runtime: RuntimeState,
    fuel: nat
  ): RuntimeState
    decreases fuel
  {
    if fuel == 0 || |runtime.stack| == 0 then
      runtime
    else
      RuntimeRun(
        program,
        RuntimeStep(program, runtime).state,
        fuel - 1
      )
  }

  lemma RuntimeRunRefinesSpecification(
    program: R.Program,
    specification: R.State,
    runtime: RuntimeState,
    fuel: nat
  )
    requires Refines(specification, runtime)
    ensures Refines(
              R.Run(program, specification, fuel),
              RuntimeRun(program, runtime, fuel)
            )
    decreases fuel
  {
    if fuel > 0 && |runtime.stack| > 0 {
      RuntimeStepRefinesSpecification(
        program, specification, runtime
      );
      RuntimeRunRefinesSpecification(
        program,
        R.Step(program, specification),
        RuntimeStep(program, runtime).state,
        fuel - 1
      );
    }
  }

  lemma RuntimeRunComposition(
    program: R.Program,
    runtime: RuntimeState,
    first: nat,
    second: nat
  )
    ensures RuntimeRun(
              program,
              RuntimeRun(program, runtime, first),
              second
            ) ==
            RuntimeRun(program, runtime, first + second)
    decreases first
  {
    if first > 0 && |runtime.stack| > 0 {
      RuntimeRunComposition(
        program,
        RuntimeStep(program, runtime).state,
        first - 1,
        second
      );
    } else if |runtime.stack| == 0 {
      assert RuntimeRun(program, runtime, first) == runtime;
      assert RuntimeRun(program, runtime, first + second) == runtime;
    }
  }

  lemma RuntimeDiscoveredCountMatchesObservation(
    specification: R.State,
    runtime: RuntimeState
  )
    requires Refines(specification, runtime)
    ensures runtime.discovered == |specification.results|
  {
  }
}
