/*
 * Copyright (c) 2015-2020, David A. Bauer. All rights reserved.
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
package io.actor4j.core.features.pod;

import java.util.UUID;

import io.actor4j.core.messages.ActorMessage;
import io.actor4j.core.pods.PodContext;
import io.actor4j.core.pods.actors.PodChildActor;

import static io.actor4j.core.utils.ActorLogger.*;

public class HelloActor extends PodChildActor {
	public HelloActor(UUID groupId, PodContext context) {
		super(groupId, context);
	}

	@Override
	public void preStart() {
		setAlias("hello"+groupId);
	}

	@Override
	public void receive(ActorMessage<?> message) {
		logger().debug(message.value);
		tell(String.format("Hello %s! [domain:%s, primaryReplica:%s, groupId:%s]", 
				message.value, 
				context.getDomain(),
				context.isPrimaryReplica(),
				groupId)
				, 42, message.source, message.interaction);
	}
}
