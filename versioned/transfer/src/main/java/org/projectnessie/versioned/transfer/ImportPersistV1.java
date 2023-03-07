/*
 * Copyright (C) 2022 Dremio
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
package org.projectnessie.versioned.transfer;

import static java.util.Objects.requireNonNull;
import static org.projectnessie.versioned.storage.common.indexes.StoreIndexes.newStoreIndex;
import static org.projectnessie.versioned.storage.common.logic.Logics.referenceLogic;
import static org.projectnessie.versioned.storage.common.logic.Logics.repositoryLogic;
import static org.projectnessie.versioned.storage.common.objtypes.CommitObj.commitBuilder;
import static org.projectnessie.versioned.storage.versionstore.TypeMapping.headersFromCommitMeta;
import static org.projectnessie.versioned.storage.versionstore.TypeMapping.keyToStoreKey;

import java.io.IOException;
import java.io.InputStream;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.versioned.Key;
import org.projectnessie.versioned.storage.common.exceptions.RefAlreadyExistsException;
import org.projectnessie.versioned.storage.common.exceptions.RetryTimeoutException;
import org.projectnessie.versioned.storage.common.indexes.StoreIndex;
import org.projectnessie.versioned.storage.common.indexes.StoreKey;
import org.projectnessie.versioned.storage.common.logic.ReferenceLogic;
import org.projectnessie.versioned.storage.common.objtypes.CommitObj;
import org.projectnessie.versioned.storage.common.objtypes.CommitOp;
import org.projectnessie.versioned.storage.common.persist.Obj;
import org.projectnessie.versioned.storage.common.persist.ObjId;
import org.projectnessie.versioned.storage.common.persist.Persist;
import org.projectnessie.versioned.storage.versionstore.RefMapping;
import org.projectnessie.versioned.transfer.serialize.TransferTypes.Commit;
import org.projectnessie.versioned.transfer.serialize.TransferTypes.ExportMeta;
import org.projectnessie.versioned.transfer.serialize.TransferTypes.NamedReference;

final class ImportPersistV1 extends ImportPersistCommon {

  ImportPersistV1(ExportMeta exportMeta, NessieImporter importer) {
    super(exportMeta, importer);
  }

  @Override
  void prepareRepository() {
    Persist persist = requireNonNull(importer.persist());
    persist.erase();
    repositoryLogic(persist).initialize("main", false, b -> {});
  }

  @Override
  long importNamedReferences() throws IOException {
    long namedReferenceCount = 0L;
    ReferenceLogic refLogic = referenceLogic(requireNonNull(importer.persist()));
    for (String fileName : exportMeta.getNamedReferencesFilesList()) {
      try (InputStream input = importFiles.newFileInput(fileName)) {
        while (true) {
          NamedReference namedReference = NamedReference.parseDelimitedFrom(input);
          if (namedReference == null) {
            break;
          }

          String ref;
          switch (namedReference.getRefType()) {
            case Tag:
              ref = RefMapping.REFS_TAGS + namedReference.getName();
              break;
            case Branch:
              ref = RefMapping.REFS_HEADS + namedReference.getName();
              break;
            default:
              throw new IllegalArgumentException("Unknown reference type " + namedReference);
          }

          try {
            refLogic.createReference(ref, ObjId.objIdFromBytes(namedReference.getCommitId()));
          } catch (RefAlreadyExistsException | RetryTimeoutException e) {
            throw new RuntimeException(e);
          }

          namedReferenceCount++;
          importer.progressListener().progress(ProgressEvent.NAMED_REFERENCE_WRITTEN);
        }
      }
    }
    return namedReferenceCount;
  }

  @Override
  void processCommit(BatchWriter<Obj> batchWriter, Commit commit) throws IOException {
    CommitMeta metadata;
    try (InputStream in = commit.getMetadata().newInput()) {
      metadata = importer.objectMapper().readValue(in, CommitMeta.class);
    }

    CommitObj.Builder c =
        commitBuilder()
            .id(ObjId.objIdFromBytes(commit.getCommitId()))
            .addTail(ObjId.objIdFromBytes(commit.getParentCommitId()))
            .created(commit.getCreatedTimeMicros())
            .seq(commit.getCommitSequence())
            .message(metadata.getMessage())
            .headers(headersFromCommitMeta(metadata))
            .incompleteIndex(true);
    commit
        .getAdditionalParentsList()
        .forEach(ap -> c.addSecondaryParents(ObjId.objIdFromBytes(ap)));
    StoreIndex<CommitOp> index = newStoreIndex(CommitOp.COMMIT_OP_SERIALIZER);
    commit
        .getOperationsList()
        .forEach(
            op -> {
              StoreKey storeKey = keyToStoreKey(Key.of(op.getContentKeyList()));
              processCommitOp(batchWriter, index, op, storeKey);
            });

    c.incrementalIndex(index.serialize());

    batchWriter.add(c.build());

    importer.progressListener().progress(ProgressEvent.COMMIT_WRITTEN);
  }
}
