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

import org.urbcomp.start.db.infra.{BaseExecutor, MetadataResult}
import org.urbcomp.start.db.metadata.AccessorFactory
import org.urbcomp.start.db.metadata.accessor.DatabaseAccessor
import org.urbcomp.start.db.metadata.entity.Database

import java.util
import scala.collection.JavaConverters.asScalaBufferConverter

case class ShowDatabaseExecutor() extends BaseExecutor {

  override def execute[Array](): MetadataResult[Array] = {
    // TODO userName context
    val userName = "start_db"
    // TODO close accessor
    val userAccessor = AccessorFactory.getUserAccessor
    val user = userAccessor.selectByFidAndName(-1 /* not used */, userName, true)

    val databaseAccessor: DatabaseAccessor = AccessorFactory.getDatabaseAccessor
    val all: util.List[Database] = databaseAccessor.selectAllByFid(user.getId, true)
    val dbs = all.asScala.map(d => Array(d.getName.asInstanceOf[AnyRef])).toArray
    MetadataResult
      .buildResult(Array("Database"), java.util.Arrays.asList(dbs: _*))
      .asInstanceOf[MetadataResult[Array]]
  }
}
