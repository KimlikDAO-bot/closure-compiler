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

package com.google.javascript.jscomp.graph;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class DominatorTreeTest {

  @Test
  public void testSimpleChain() {
    // A -> B -> C
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode("A");
    graph.createNode("B");
    graph.createNode("C");
    graph.connect("A", "->", "B");
    graph.connect("B", "->", "C");

    DominatorTree<String> tree = DominatorTree.compute(graph, "A");

    assertThat(tree.getImmediateDominator("A")).isNull();
    assertThat(tree.getImmediateDominator("B")).isEqualTo("A");
    assertThat(tree.getImmediateDominator("C")).isEqualTo("B");

    assertThat(tree.getDominatedSubtreeSize("A")).isEqualTo(3);
    assertThat(tree.getDominatedSubtreeSize("B")).isEqualTo(2);
    assertThat(tree.getDominatedSubtreeSize("C")).isEqualTo(1);

    assertThat(tree.getAllSubtreeSizes()).containsExactly("A", 3, "B", 2, "C", 1);
  }

  @Test
  public void testDiamond() {
    // A -> {B, C} -> D
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode("A");
    graph.createNode("B");
    graph.createNode("C");
    graph.createNode("D");
    graph.connect("A", "->", "B");
    graph.connect("A", "->", "C");
    graph.connect("B", "->", "D");
    graph.connect("C", "->", "D");

    DominatorTree<String> tree = DominatorTree.compute(graph, "A");

    assertThat(tree.getImmediateDominator("B")).isEqualTo("A");
    assertThat(tree.getImmediateDominator("C")).isEqualTo("A");
    assertThat(tree.getImmediateDominator("D")).isEqualTo("A");

    assertThat(tree.getDominatedSubtreeSize("A")).isEqualTo(4);
    assertThat(tree.getDominatedSubtreeSize("B")).isEqualTo(1);
    assertThat(tree.getDominatedSubtreeSize("C")).isEqualTo(1);
    assertThat(tree.getDominatedSubtreeSize("D")).isEqualTo(1);
  }

  @Test
  public void testCycle() {
    // A -> B -> C -> B
    // Arbitrarily chooses 'B' as the dominator of both 'B' and 'C'.
    // NOTE: in practice, we added DominatorTree for analyzing dependencies in JSCompiler. Cycles
    // between goog.require/goog.requireTyped files can only occur within a single library, where
    // it is valid to have goog.requireType cycles.
    // So this result is not entirely precise, but is still useful for practical purposes.
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode("A");
    graph.createNode("B");
    graph.createNode("C");
    graph.connect("A", "->", "B");
    graph.connect("B", "->", "C");
    graph.connect("C", "->", "B");

    DominatorTree<String> tree = DominatorTree.compute(graph, "A");

    assertThat(tree.getImmediateDominator("B")).isEqualTo("A");
    assertThat(tree.getImmediateDominator("C")).isEqualTo("B");

    assertThat(tree.getDominatedSubtreeSize("A")).isEqualTo(3);
    assertThat(tree.getDominatedSubtreeSize("B")).isEqualTo(2);
    assertThat(tree.getDominatedSubtreeSize("C")).isEqualTo(1);
  }

  @Test
  public void testBottleneck() {
    // Entry -> A -> B -> {C, D, E}
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode("Entry");
    graph.createNode("A");
    graph.createNode("B");
    graph.createNode("C");
    graph.createNode("D");
    graph.createNode("E");
    graph.connect("Entry", "->", "A");
    graph.connect("A", "->", "B");
    graph.connect("B", "->", "C");
    graph.connect("B", "->", "D");
    graph.connect("B", "->", "E");

    DominatorTree<String> tree = DominatorTree.compute(graph, "Entry");

    assertThat(tree.getImmediateDominator("B")).isEqualTo("A");
    assertThat(tree.getDominatedSubtreeSize("B")).isEqualTo(4); // B, C, D, E
    assertThat(tree.getDominatedSubtreeSize("A")).isEqualTo(5); // A, B, C, D, E
  }

  @Test
  public void testSharedBottleneck() {
    // Entry -> {R1, R2}
    // R1 -> B
    // R2 -> B
    // B -> {C, D}
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode("Entry");
    graph.createNode("R1");
    graph.createNode("R2");
    graph.createNode("B");
    graph.createNode("C");
    graph.createNode("D");
    graph.connect("Entry", "->", "R1");
    graph.connect("Entry", "->", "R2");
    graph.connect("R1", "->", "B");
    graph.connect("R2", "->", "B");
    graph.connect("B", "->", "C");
    graph.connect("B", "->", "D");

    DominatorTree<String> tree = DominatorTree.compute(graph, "Entry");

    assertThat(tree.getImmediateDominator("B")).isEqualTo("Entry");
    // Neither R1 nor R2 dominate "B", as there is always a path from Entry to B going through the
    // other.
    assertThat(tree.getDominatedSubtreeSize("R1")).isEqualTo(1); // R1
    assertThat(tree.getDominatedSubtreeSize("R2")).isEqualTo(1); // R2
    assertThat(tree.getDominatedSubtreeSize("B")).isEqualTo(3); // B, C, D
  }

  @Test
  public void testNodeNotReachableFromEntryPoint_doesNotCountAsDominator() {
    // A -> B
    // OTHER -> B
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode("A");
    graph.createNode("B");
    graph.createNode("OTHER");
    graph.connect("A", "->", "B");
    graph.connect("OTHER", "->", "B");

    DominatorTree<String> tree = DominatorTree.compute(graph, "A");

    // The dominator tree does not consider "OTHER" to dominate "B", as it is only looking
    // at paths *from the specified entry point* "A" to "B".
    assertThat(tree.getImmediateDominator("B")).isEqualTo("A");
    assertThat(tree.getImmediateDominator("OTHER")).isNull();
    assertThat(tree.getDominatedSubtreeSize("OTHER")).isEqualTo(0);

    assertThat(tree.getAllSubtreeSizes()).containsExactly("A", 2, "B", 1);
  }

  @Test
  public void testGetImmediateDominator_nonExistentElement_returnsNull() {
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode("A");

    DominatorTree<String> tree = DominatorTree.compute(graph, "A");

    assertThat(tree.getImmediateDominator("FOO")).isNull();
  }

  @Test
  public void testGetSubtreeSize_nonExistentElement_defaultsToZero() {
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode("A");

    DominatorTree<String> tree = DominatorTree.compute(graph, "A");

    assertThat(tree.getDominatedSubtreeSize("FOO")).isEqualTo(0);
  }
}
