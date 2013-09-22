/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
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
package com.orientechnologies.orient.server.distributed.task;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.concurrent.locks.Lock;

import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.tool.ODatabaseExport;
import com.orientechnologies.orient.server.OServer;
import com.orientechnologies.orient.server.distributed.ODistributedRequest.EXECUTION_MODE;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog.DIRECTION;
import com.orientechnologies.orient.server.distributed.ODistributedServerManager;

/**
 * Ask for deployment of database from a remote node.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public class ODeployDatabaseTask extends OAbstractReplicatedTask {
  private static final long   serialVersionUID = 1L;
  private static final String BACKUP_DIRECTORY = "tempBackups";

  protected final static int  CHUNK_MAX_SIZE   = 1048576;      // 1MB

  public ODeployDatabaseTask() {
  }

  @Override
  public ODeployDatabaseTask copy() {
    final ODeployDatabaseTask copy = (ODeployDatabaseTask) super.copy(new ODeployDatabaseTask());
    return copy;
  }

  @Override
  public Object execute(final OServer iServer, ODistributedServerManager iManager, final ODatabaseDocumentTx database)
      throws Exception {

    final Lock lock = iManager.getLock(database.getName());
    if (lock.tryLock()) {
      try {
        ODistributedServerLog.warn(this, iManager.getLocalNodeName(), getNodeSource(), DIRECTION.OUT, "backuping database %s...",
            database.getName());

        final File f = new File(BACKUP_DIRECTORY + "/" + database.getName());

        try {
          database.freeze();
          try {
            ODistributedServerLog.warn(this, iManager.getLocalNodeName(), getNodeSource(), DIRECTION.OUT,
                "exporting database %s to %s...", database.getName(), f);

            final ODatabaseExport export = new ODatabaseExport(database, f.getAbsolutePath(), null);
            try {
              export.exportDatabase();
            } finally {
              export.close();
            }
          } finally {
            database.release();
          }

          ODistributedServerLog.warn(this, iManager.getLocalNodeName(), getNodeSource(), DIRECTION.OUT,
              "exporting database %s to %s...", database.getName(), f);

          final ByteArrayOutputStream out = new ByteArrayOutputStream(CHUNK_MAX_SIZE);
          final FileInputStream in = new FileInputStream(f);
          try {
            final long fileSize = f.length();

            ODistributedServerLog.warn(this, iManager.getLocalNodeName(), getNodeSource(), DIRECTION.OUT,
                "copying %s bytes to remote node...", OFileUtils.getSizeAsString(fileSize));

            for (int byteCopied = 0; byteCopied < fileSize;) {
              byteCopied += OIOUtils.copyStream(in, out, CHUNK_MAX_SIZE);

              if ((Boolean) iManager.sendRequest(database.getName(), null, new OCopyDatabaseChunkTask(out.toByteArray()),
                  EXECUTION_MODE.RESPONSE)) {
                out.reset();
              }
            }

            return "deployed";
          } finally {
            out.close();
          }
        } finally {
          OFileUtils.deleteRecursively(new File("exportDatabase/"));
        }

      } finally {
        lock.unlock();
      }

    } else
      ODistributedServerLog.debug(this, iManager.getLocalNodeName(), getNodeSource(), DIRECTION.NONE,
          "skip deploying database because another node is doing it", database.getName());

    return "skipped";
  }

  @Override
  public String getPayload() {
    return null;
  }

  @Override
  public String getName() {
    return "deploy_db";
  }

  @Override
  public void writeExternal(final ObjectOutput out) throws IOException {
  }

  @Override
  public void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException {
  }
}
