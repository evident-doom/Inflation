package inflation

/**
 * Names a part of the model for the profiler ([Profile], docs/profiling.md): only a label, it does nothing when the code
 * runs. `ProfileReport` reads it by reflection and gives each sample of a profile=jfr recording to the innermost
 * labelled method on its stack, so the function itself carries no timing calls. Names are unique ([PartNamesTest]);
 * do not label an `inline` function (it has no stack frame to sample).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Part(val name: String)
