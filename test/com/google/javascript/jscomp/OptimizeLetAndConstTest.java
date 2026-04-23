/*
 * Copyright 2026 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link OptimizeLetAndConst}. */
@RunWith(JUnit4.class)
public final class OptimizeLetAndConstTest extends CompilerTestCase {
  private boolean assumeOutputIsWrapped;

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeOutputIsWrapped = false;
  }

  @Override
  protected CompilerPass getProcessor(Compiler compiler) {
    return new PeepholeOptimizationsPass(
        compiler,
        "testOptimizeLetAndConst",
        new OptimizeLetAndConstPeephole(assumeOutputIsWrapped));
  }

  @Test
  public void testConstToLetInBlock() {
    test("if (true) { const x = 1; }", "if (true) { let x = 1; }");
  }

  @Test
  public void testConstToVarInFunction() {
    test("function f() { const x = 1; }", "function f() { var x = 1; }");
  }

  @Test
  public void testLetToVarInFunction() {
    test("function f() { let x = 1; }", "function f() { var x = 1; }");
  }

  @Test
  public void testLetToVarInBlock() {
    testSame("function f() { if (true) { let x = 1; } }");
  }

  @Test
  public void testConstToLetGlobalUnwrapped() {
    assumeOutputIsWrapped = false;
    testSame("const x = 1;");
  }

  @Test
  public void testConstToVarGlobalWrapped() {
    assumeOutputIsWrapped = true;
    test("const x = 1;", "var x = 1;");
  }

  @Test
  public void testLetToVarGlobalWrapped() {
    assumeOutputIsWrapped = true;
    test("let x = 1;", "var x = 1;");
  }

  @Test
  public void testLetInLoopNotConvertedToVar() {
    // let in loop header and loop body stay let
    testSame("function f() { for (let i = 0; i < 10; i++) { let x = 1; } }");
  }

  @Test
  public void testConstToLetInLoopConvertedToLetButNotVar() {
    test(
        "function f() { for (let i = 0; i < 10; i++) { const x = 1; } }",
        "function f() { for (let i = 0; i < 10; i++) { let x = 1; } }");
  }

  @Test
  public void testDestructuringConstToLet() {
    test("if (true) { const [a, b] = [1, 2]; }", "if (true) { let [a, b] = [1, 2]; }");
  }

  @Test
  public void testDestructuringLetToVarInFunction() {
    test("function f() { let [a, b] = [1, 2]; }", "function f() { var [a, b] = [1, 2]; }");
  }

  @Test
  public void testClassStaticBlockLetAndConst() {
    test(
        "class C { static { const x = 1; let y = 2; } }",
        "class C { static { var x = 1; var y = 2; } }");
  }

  @Test
  public void testCatchBlockConstAndLet() {
    test(
        "function f() { try {} catch (e) { const x = 1; let y = 2; } }",
        "function f() { try {} catch (e) { let x = 1; let y = 2; } }");
  }

  @Test
  public void testSwitchConstAndLet() {
    test(
        "function f(x) { switch (x) { case 1: const y = 1; let z = 2; } }",
        "function f(x) { switch (x) { case 1: let y = 1; let z = 2; } }");
  }
}
