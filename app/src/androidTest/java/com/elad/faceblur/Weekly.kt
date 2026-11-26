package com.elad.faceblur

/**
 * A test that runs only in the weekly (and manual) emulator run, not on every merge into develop
 * or main: screenshot tests for eyeballing, with few or no assertions. CI's merge runs pass
 * `-e notAnnotation com.elad.faceblur.Weekly`; the weekly run passes nothing and runs them all.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Weekly
