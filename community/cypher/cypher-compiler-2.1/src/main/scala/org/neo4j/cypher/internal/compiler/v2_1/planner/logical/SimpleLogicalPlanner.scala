/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_1.planner.logical

import org.neo4j.cypher.internal.compiler.v2_1.planner._

trait NodeIdentifierInitialiser {
  def apply()(implicit context: LogicalPlanContext): PlanTable
}

trait MainLoop {
  def apply(plan: PlanTable)(implicit context: LogicalPlanContext): PlanTable
}

trait ProjectionApplicator {
  def apply(plan: LogicalPlan)(implicit context: LogicalPlanContext): LogicalPlan
}

trait SelectionApplicator {
  def apply(plan: LogicalPlan)(implicit context: LogicalPlanContext): LogicalPlan
}

class SimpleLogicalPlanner(startPointFinder: NodeIdentifierInitialiser = new initialiser(applySelections),
                           mainLoop: MainLoop = new expandAndJoin(applySelections),
                           projector: ProjectionApplicator = projectionPlanner) {
  def plan(implicit context: LogicalPlanContext): LogicalPlan = {
    val initialPlanTable = startPointFinder()

    val bestPlan = if (initialPlanTable.isEmpty)
      SingleRow()
    else {
      val convergedPlans = if (initialPlanTable.size > 1) {
        mainLoop(initialPlanTable)
      } else {
        initialPlanTable
      }

      if(convergedPlans.size > 1)
        throw new CantHandleQueryException

      val bestPlan: LogicalPlan = convergedPlans.plans.head
      if (!context.queryGraph.selections.coveredBy(bestPlan.solvedPredicates))
        throw new CantHandleQueryException

      bestPlan
    }

    projector(bestPlan)
  }
}