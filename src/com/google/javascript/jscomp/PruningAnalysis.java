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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Comparator.comparingInt;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.deps.DependencyInfo.Require;
import com.google.javascript.jscomp.deps.SortedDependencies;
import com.google.javascript.jscomp.graph.DiGraph;
import com.google.javascript.jscomp.graph.DominatorTree;
import com.google.javascript.jscomp.graph.LinkedDirectedGraph;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Performs analysis on pruned dependencies to identify which root requirements are pulling in the
 * most transitive files.
 *
 * <p>This is intended to help developers understand the size of their dependency graph better, and
 * make decisions about how to optimize compilation performance by reducing the number of inputs.
 */
final class PruningAnalysis {

  // Limit the size of the "bottleneck" report to the top N files.
  private static final int BOTTLENECK_THRESHOLD = 25;

  /**
   * Results of the pruning analysis.
   *
   * @param entryPointDependencyCount A map of entry point file name to the number of files that are
   *     pulled in by that root, including shared dependencies.
   * @param bottleneckBlame A map of the top N files that are considered "bottlenecks" in the
   *     dependency graph, along with how many files are pulled in by that bottleneck (i.e. how many
   *     files are "dominated" by that bottleneck in a dominator tree analysis.)
   */
  record Result(
      ImmutableMap<String, Integer> entryPointDependencyCount,
      ImmutableMap<String, Integer> bottleneckBlame) {}

  private final SortedDependencies<CompilerInput> sorter;
  private final ImmutableSet<CompilerInput> entryPoints;

  private PruningAnalysis(
      SortedDependencies<CompilerInput> sorter, Collection<CompilerInput> entryPoints) {
    this.sorter = sorter;
    this.entryPoints = ImmutableSet.copyOf(entryPoints);
  }

  static PruningAnalysis create(
      SortedDependencies<CompilerInput> sorter, Collection<CompilerInput> entryPoints) {
    return new PruningAnalysis(sorter, entryPoints);
  }

  /**
   * Analyzes the dependency graph to identify possible areas of code reduction by pruning
   * dependencies.
   */
  Result analyze() {
    var total = calculateTotalBlame();
    var bottlenecks = calculateBottlenecks();

    return new Result(total, bottlenecks);
  }

  private ImmutableMap<String, Integer> calculateTotalBlame() {
    ImmutableMap.Builder<String, Integer> reachabilityPerEntryPoint = ImmutableMap.builder();
    for (CompilerInput provider : entryPoints) {
      var transitiveClosure =
          ImmutableSet.<CompilerInput>builder()
              .addAll(
                  sorter.getStrongDependenciesOf(ImmutableList.of(provider), /* sorted= */ false))
              .addAll(sorter.getSortedWeakDependenciesOf(ImmutableList.of(provider)))
              .build();
      reachabilityPerEntryPoint.put(provider.getName(), transitiveClosure.size());
    }
    return reachabilityPerEntryPoint.buildOrThrow();
  }

  private static final String VIRTUAL_ROOT = "ENTRY POINTS ROOT";

  /**
   * Calculates the top N files that are considered "bottlenecks" in the dependency graph, along
   * with how many files are pulled in by that bottleneck.
   */
  private ImmutableMap<String, Integer> calculateBottlenecks() {
    DiGraph<String, String> graph = buildGraph();

    DominatorTree<String> dominatorTree = DominatorTree.compute(graph, VIRTUAL_ROOT);

    Map<String, Integer> bottleneckBlame = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : dominatorTree.getAllSubtreeSizes().entrySet()) {
      String node = entry.getKey();
      int retainedCount = entry.getValue();
      if (!node.equals(VIRTUAL_ROOT) && retainedCount > 1) {
        bottleneckBlame.put(node, retainedCount);
      }
    }

    return bottleneckBlame.entrySet().stream()
        .sorted(comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed())
        .limit(BOTTLENECK_THRESHOLD)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private DiGraph<String, String> buildGraph() {
    DiGraph<String, String> graph = LinkedDirectedGraph.create();
    graph.createNode(VIRTUAL_ROOT);

    for (CompilerInput input : sorter.getSortedList()) {
      graph.createNode(input.getName());
    }

    for (CompilerInput input : entryPoints) {
      graph.connect(VIRTUAL_ROOT, null, input.getName());
    }

    for (CompilerInput input : sorter.getSortedList()) {
      String name = input.getName();
      Set<String> depSymbols = new LinkedHashSet<>();
      for (Require req : input.getRequires()) {
        depSymbols.add(req.getSymbol());
      }
      depSymbols.addAll(input.getTypeRequires());

      for (String symbol : depSymbols) {
        try {
          CompilerInput dep = sorter.getInputProviding(symbol);
          if (!dep.equals(input)) {
            graph.connectIfNotFound(name, null, dep.getName());
          }
        } catch (SortedDependencies.MissingProvideException e) {
          // Skip missing symbols.
        }
      }
    }
    return graph;
  }
}
