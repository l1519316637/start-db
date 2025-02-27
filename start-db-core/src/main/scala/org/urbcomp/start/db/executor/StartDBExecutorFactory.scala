/*
 * Copyright 2022 ST-Lab
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 */

package org.urbcomp.start.db.executor

import org.apache.calcite.sql.ddl.SqlCreateTable
import org.apache.calcite.sql.{SqlInsert, SqlNode, SqlUpdate}
import org.urbcomp.start.db.infra.{BaseExecutor, BaseExecutorFactory}
import org.urbcomp.start.db.parser.ddl.SqlCreateDatabase
import org.urbcomp.start.db.parser.dql.{SqlShowCreateTable, SqlShowDatabases, SqlShowStatus}

class StartDBExecutorFactory extends BaseExecutorFactory {
  override def convertExecutor(node: SqlNode): BaseExecutor = node match {
    case n: SqlShowCreateTable => ShowCreateTableExecutor(n)
    case n: SqlUpdate          => UpdateExecutor(n)
    case n: SqlInsert          => InsertExecutor(n)
    case n: SqlCreateDatabase  => CreateDatabaseExecutor(n)
    case n: SqlCreateTable     => CreateTableExecutor(n)
    case _: SqlShowDatabases   => ShowDatabaseExecutor()
    case _: SqlShowStatus      => ShowStatusExecutor()
  }
}
