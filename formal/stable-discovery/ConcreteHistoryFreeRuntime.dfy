// Combined runtime representation: right-edge stack, exact admission set,
// scalar discovered count, and one optional step output. This closes the seam
// between history erasure and the concrete stack layout used by the hot loop.
// Exploratory proof model; intentionally excluded from release artifacts.
include "HistoryFreeReducer.dfy"
include "RuntimeStackRefinement.dfy"

module ConcreteHistoryFreeRuntime {
  import R = StableReducer
  import H = HistoryFreeReducer
  import S = RuntimeStackRefinement

  datatype ConcreteState = ConcreteState(
    rightStack: seq<nat>,
    admitted: set<nat>,
    discovered: nat
  )

  datatype ConcreteTransition = ConcreteTransition(
    state: ConcreteState,
    output: H.MaybeOutput
  )

  predicate Represents(
    runtime: H.RuntimeState,
    concrete: ConcreteState
  ) {
    S.Represents(concrete.rightStack, runtime.stack) &&
    concrete.admitted == runtime.admitted &&
    concrete.discovered == runtime.discovered
  }

  function Step(
    program: R.Program,
    concrete: ConcreteState
  ): ConcreteTransition {
    if |concrete.rightStack| == 0 then
      ConcreteTransition(concrete, H.NoOutput)
    else
      var node := concrete.rightStack[|concrete.rightStack| - 1];
      var admission := R.Admit(
                         R.Successors(program, node),
                         concrete.admitted
                       );
      var next := ConcreteState(
                    S.PopAndPushCanonical(
                      concrete.rightStack,
                      admission.newValues
                    ),
                    admission.seen,
                    concrete.discovered +
                      (if node in program.resultNodes then 1 else 0)
                  );
      ConcreteTransition(
        next,
        if node in program.resultNodes
        then H.Output(node)
        else H.NoOutput
      )
  }

  function Run(
    program: R.Program,
    concrete: ConcreteState,
    fuel: nat
  ): ConcreteState
    decreases fuel
  {
    if fuel == 0 || |concrete.rightStack| == 0 then concrete
    else Run(program, Step(program, concrete).state, fuel - 1)
  }

  lemma EmptyConcreteIffEmptyAbstract(
    runtime: H.RuntimeState,
    concrete: ConcreteState
  )
    requires Represents(runtime, concrete)
    ensures |concrete.rightStack| == 0 <==> |runtime.stack| == 0
  {
    S.ReverseLength(runtime.stack);
  }

  lemma ConcreteHeadIsAbstractHead(
    runtime: H.RuntimeState,
    concrete: ConcreteState
  )
    requires Represents(runtime, concrete)
    requires |runtime.stack| > 0
    ensures |concrete.rightStack| > 0
    ensures concrete.rightStack[|concrete.rightStack| - 1] ==
              runtime.stack[0]
  {
    S.RightHeadRefinesAbstractHead(
      concrete.rightStack, runtime.stack
    );
  }

  lemma ConcreteStepRefinesRuntimeStep(
    program: R.Program,
    runtime: H.RuntimeState,
    concrete: ConcreteState
  )
    requires Represents(runtime, concrete)
    ensures Represents(
              H.RuntimeStep(program, runtime).state,
              Step(program, concrete).state
            )
    ensures H.RuntimeStep(program, runtime).output ==
              Step(program, concrete).output
  {
    EmptyConcreteIffEmptyAbstract(runtime, concrete);
    if |runtime.stack| > 0 {
      ConcreteHeadIsAbstractHead(runtime, concrete);
      var node := runtime.stack[0];
      var admission := R.Admit(
                         R.Successors(program, node),
                         runtime.admitted
                       );
      assert concrete.admitted == runtime.admitted;
      S.PopPushRefinesCanonicalReplacement(
        concrete.rightStack,
        runtime.stack,
        admission.newValues
      );
    }
  }

  lemma ConcreteRunRefinesRuntimeRun(
    program: R.Program,
    runtime: H.RuntimeState,
    concrete: ConcreteState,
    fuel: nat
  )
    requires Represents(runtime, concrete)
    ensures Represents(
              H.RuntimeRun(program, runtime, fuel),
              Run(program, concrete, fuel)
            )
    decreases fuel
  {
    if fuel > 0 && |runtime.stack| > 0 {
      ConcreteStepRefinesRuntimeStep(program, runtime, concrete);
      ConcreteRunRefinesRuntimeRun(
        program,
        H.RuntimeStep(program, runtime).state,
        Step(program, concrete).state,
        fuel - 1
      );
    } else {
      EmptyConcreteIffEmptyAbstract(runtime, concrete);
    }
  }
}
