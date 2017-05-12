/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.inspections

import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.util.Processor
import com.intellij.util.containers.SortedList
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyUtil
import com.jetbrains.python.pyi.PyiFile
import com.jetbrains.python.pyi.PyiTypeProvider
import java.util.*

class PyOverloadsInspection : PyInspection() {

  override fun buildVisitor(holder: ProblemsHolder,
                            isOnTheFly: Boolean,
                            session: LocalInspectionToolSession): PsiElementVisitor = Visitor(holder, session)

  private class Visitor(holder: ProblemsHolder, session: LocalInspectionToolSession) : PyInspectionVisitor(holder, session) {

    override fun visitPyClass(node: PyClass?) {
      if (node?.containingFile is PyiFile) return

      super.visitPyClass(node)

      if (node != null) {
        processScope(node, { node.visitMethods(it, false, myTypeEvalContext) })
      }
    }

    override fun visitPyFile(node: PyFile?) {
      if (node is PyiFile) return

      super.visitPyFile(node)

      if (node != null) {
        processScope(node, { processor -> node.topLevelFunctions.forEach { processor.process(it) } })
      }
    }

    private fun processScope(owner: ScopeOwner, processorUsage: (GroupingFunctionsByNameProcessor) -> Unit) {
      val processor = GroupingFunctionsByNameProcessor()
      processorUsage(processor)
      processor.result.values.forEach { processSameNameFunctions(owner, it) }
    }

    private fun processSameNameFunctions(owner: ScopeOwner, functions: List<PyFunction>) {
      if (functions.find { PyiTypeProvider.isOverload(it, myTypeEvalContext) } == null) return

      val implementation = functions.lastOrNull { !PyiTypeProvider.isOverload(it, myTypeEvalContext) }

      if (implementation == null) {
        functions
          .maxBy { it.textOffset }
          ?.let {
            registerProblem(it.nameIdentifier,
                            "A series of @overload-decorated ${chooseBetweenFunctionsAndMethods(owner)} " +
                            "should always be followed by an implementation that is not @overload-ed")
          }
      }
      else {
        if (implementation != functions.last()) {
          registerProblem(functions.last().nameIdentifier,
                          "A series of @overload-decorated ${chooseBetweenFunctionsAndMethods(owner)} " +
                          "should always be followed by an implementation that is not @overload-ed")
        }

        functions
          .asSequence()
          .filter { isIncompatibleOverload(implementation, it) }
          .forEach {
            registerProblem(it.nameIdentifier,
                            "Signature of this @overload-decorated ${chooseBetweenFunctionAndMethod(owner)} " +
                            "is not compatible with the implementation")
          }
      }
    }

    private fun chooseBetweenFunctionsAndMethods(owner: ScopeOwner) = if (owner is PyClass) "methods" else "functions"

    private fun chooseBetweenFunctionAndMethod(owner: ScopeOwner) = if (owner is PyClass) "method" else "function"

    private fun isIncompatibleOverload(implementation: PyFunction, overload: PyFunction): Boolean {
      return implementation != overload &&
             PyiTypeProvider.isOverload(overload, myTypeEvalContext) &&
             !PyUtil.isSignatureCompatibleTo(implementation, overload, myTypeEvalContext)
    }
  }

  private class GroupingFunctionsByNameProcessor : Processor<PyFunction> {

    val result: MutableMap<String, MutableList<PyFunction>> = HashMap()

    override fun process(t: PyFunction?): Boolean {
      val name = t?.name
      if (name != null) {
        result
          .getOrPut(name, { SortedList<PyFunction> { f1, f2 -> f1.textOffset - f2.textOffset } })
          .add(t!!)
      }

      return true
    }
  }
}