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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the dominator tree of a directed graph.
 *
 * <p>A node d dominates a node n if every path from the entry node to n must go through d.
 *
 * <p>The "immediate dominator" of n is the unique dominator d of n such that d != n and d is
 * dominated by every other dominator of n (excluding n itself).
 *
 * <p>This implementation uses the iterative algorithm by Cooper, Harvey, and Kennedy. See <a
 * href="https://www.cs.tufts.edu/~nr/cs257/archive/keith-cooper/dom14.pdf"></a>.
 *
 * @param <N> Value type that the graph node stores.
 */
public final class DominatorTree<N> {

  // Mapping from node value to its immediate dominator's value.
  // entry node maps to null.
  private final ImmutableMap<N, N> idoms;

  // Mapping from node value to the number of nodes it dominates (including itself).
  private final ImmutableMap<N, Integer> subtreeSizes;

  private DominatorTree(Map<N, N> idoms, Map<N, Integer> subtreeSizes) {
    this.idoms = ImmutableMap.copyOf(idoms);
    this.subtreeSizes = ImmutableMap.copyOf(subtreeSizes);
  }

  /**
   * Computes the dominator tree for the given graph starting from the entry node.
   *
   * @param graph The directed graph to analyze.
   * @param entry The entry node of the graph.
   * @return A {@link DominatorTree} instance.
   */
  public static <N> DominatorTree<N> compute(DiGraph<N, ?> graph, N entry) {
    checkNotNull(graph.getNode(entry), "Entry node not in graph");

    // 1. Post-order traversal to assign indexes to nodes.
    List<N> postOrder = new ArrayList<>();
    Map<N, Integer> postOrderIndex = new LinkedHashMap<>();
    buildPostOrder(graph, entry, postOrder, new LinkedHashMap<>());

    int numNodes = postOrder.size();
    N[] reversePostOrder = toReversedArray(postOrder);

    // Map each node to its index in the post-order list.
    for (int i = 0; i < numNodes; i++) {
      postOrderIndex.put(postOrder.get(i), i);
    }

    // 2. Iterative algorithm for immediate dominators.
    int[] idomIndexes = new int[numNodes];
    Arrays.fill(idomIndexes, 0, numNodes, -1);

    int startNodeIndex = postOrderIndex.get(entry);
    idomIndexes[startNodeIndex] = startNodeIndex;

    boolean changed = true;
    while (changed) {
      changed = false;
      // Process nodes in reverse post-order (excluding entry node).
      for (N node : reversePostOrder) {
        if (node.equals(entry)) {
          continue;
        }

        int nodeIdx = postOrderIndex.get(node);
        int newIdomIdx = -1;

        // Pick first processed predecessor.
        for (DiGraphNode<N, ?> pred : graph.getDirectedPredNodes(node)) {
          N predValue = pred.getValue();
          Integer predIdx = postOrderIndex.get(predValue);
          // predIdx can be null if `predValue` is not in the graph. This is normal - it just means
          // the `predValue` isn't reachable from the given entry point.
          if (predIdx != null && idomIndexes[predIdx] != -1) {
            newIdomIdx = predIdx;
            break;
          }
        }

        if (newIdomIdx != -1) {
          // Intersect with all other processed predecessors.
          for (DiGraphNode<N, ?> pred : graph.getDirectedPredNodes(node)) {
            N predValue = pred.getValue();
            Integer predIdx = postOrderIndex.get(predValue);
            // predIdx can be null if `predValue` is not in the graph. This is normal - it just
            // means the `predValue` isn't reachable from the given entry point.
            if (predIdx != null && idomIndexes[predIdx] != -1) {
              newIdomIdx = intersect(idomIndexes, predIdx, newIdomIdx);
            }
          }

          if (idomIndexes[nodeIdx] != newIdomIdx) {
            idomIndexes[nodeIdx] = newIdomIdx;
            changed = true;
          }
        }
      }
    }

    // 3. Build the result maps.
    Map<N, N> idomsMap = new LinkedHashMap<>();
    ListMultimap<N, N> children = ArrayListMultimap.create();
    for (int i = 0; i < numNodes; i++) {
      N node = postOrder.get(i);
      int idomIdx = idomIndexes[i];
      if (idomIdx != -1 && i != startNodeIndex) {
        N idom = postOrder.get(idomIdx);
        idomsMap.put(node, idom);
        children.put(idom, node);
      }
    }

    Map<N, Integer> sizesMap = new LinkedHashMap<>();
    computeSubtreeSizes(entry, children, sizesMap);

    return new DominatorTree<>(idomsMap, sizesMap);
  }

  private static int intersect(int[] idomIndexes, int i, int j) {
    int finger1 = i;
    int finger2 = j;
    while (finger1 != finger2) {
      while (finger1 < finger2) {
        finger1 = idomIndexes[finger1];
      }
      while (finger2 < finger1) {
        finger2 = idomIndexes[finger2];
      }
    }
    return finger1;
  }

  private static <N> void buildPostOrder(
      DiGraph<N, ?> graph, N node, List<N> postOrder, Map<N, Boolean> visited) {
    visited.put(node, true);
    for (DiGraphNode<N, ?> succ : graph.getDirectedSuccNodes(node)) {
      N succValue = succ.getValue();
      if (!visited.containsKey(succValue)) {
        buildPostOrder(graph, succValue, postOrder, visited);
      }
    }
    postOrder.add(node);
  }

  @CanIgnoreReturnValue
  private static <N> int computeSubtreeSizes(
      N node, ListMultimap<N, N> children, Map<N, Integer> sizes) {
    int size = 1;
    List<N> nodeChildren = children.get(node);
    if (nodeChildren != null) {
      for (N child : nodeChildren) {
        size += computeSubtreeSizes(child, children, sizes);
      }
    }
    sizes.put(node, size);
    return size;
  }

  public N getImmediateDominator(N node) {
    return idoms.get(node);
  }

  public int getDominatedSubtreeSize(N node) {
    return subtreeSizes.getOrDefault(node, 0);
  }

  public ImmutableMap<N, Integer> getAllSubtreeSizes() {
    return subtreeSizes;
  }

  private static <N> N[] toReversedArray(List<N> list) {
    int size = list.size();
    @SuppressWarnings("unchecked") // we're immediately casting to N[] upon creation.
    N[] reversed = (N[]) new Object[size];
    for (int i = 0; i < size; i++) {
      reversed[i] = list.get(size - 1 - i);
    }
    return reversed;
  }
}
