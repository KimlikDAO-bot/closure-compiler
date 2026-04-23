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

import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * Late-stage compiler pass that lowers `const`/`let` to `var` where safe, and `const` to `let`,
 * improving keyword homogeneity for better compression.
 *
 */
class OptimizeLetAndConstPeephole extends AbstractPeepholeOptimization {

  private final boolean assumeOutputIsWrapped;

  OptimizeLetAndConstPeephole(boolean assumeOutputIsWrapped) {
    this.assumeOutputIsWrapped = assumeOutputIsWrapped;
  }

  @Override
  Node optimizeSubtree(Node subtree) {
    return switch (subtree.getToken()) {
      case LET -> processLet(subtree);
      case CONST -> processConst(subtree);
      default -> subtree;
    };
  }

  private Node processLet(Node n) {
    if (!assumeOutputIsWrapped && n.getParent().isScript()) {
      return n;
    }

    if (isDirectlyInHoistScope(n)) {
      n.setToken(Token.VAR);
      reportChangeToEnclosingScope(n);
    }
    return n;
  }

  private Node processConst(Node n) {
    if (!assumeOutputIsWrapped && n.getParent().isScript()) {
      return n;
    }

    if (isDirectlyInHoistScope(n)) {
      n.setToken(Token.VAR);
    } else {
      n.setToken(Token.LET);
      addFeatureToEnclosingScript(Feature.LET_DECLARATIONS);
    }
    reportChangeToEnclosingScope(n);
    return n;
  }

  private boolean isDirectlyInHoistScope(Node n) {
    Node parent = n.getParent();
    while (parent != null && parent.isLabel()) {
      parent = parent.getParent();
    }
    if (parent == null) {
      return false;
    }

    if (parent.isScript() || parent.isModuleBody()) {
      return true;
    }

    if (parent.isBlock()) {
      Node grandparent = parent.getParent();
      if (grandparent != null && (grandparent.isFunction() || grandparent.isClassMembers())) {
        return true;
      }
    }

    return false;
  }
}
