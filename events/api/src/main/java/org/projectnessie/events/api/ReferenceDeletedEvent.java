/*
 * Copyright (C) 2023 Dremio
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
package org.projectnessie.events.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;

/** Event that is emitted after a reference is deleted. */
@Value.Immutable
@JsonSerialize(as = ImmutableReferenceDeletedEvent.class)
@JsonDeserialize(as = ImmutableReferenceDeletedEvent.class)
public interface ReferenceDeletedEvent extends ReferenceEvent {

  @Value.Default
  @Override
  default EventType getType() {
    return EventType.REFERENCE_DELETED;
  }

  /** The hash of the reference before the deletion. */
  String getHashBefore();
}