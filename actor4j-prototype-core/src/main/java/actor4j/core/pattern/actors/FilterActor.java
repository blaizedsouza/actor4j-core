/*
 * Copyright (c) 2015-2017, David A. Bauer. All rights reserved.
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */
package actor4j.core.pattern.actors;

import java.util.UUID;
import java.util.function.BiFunction;

import actor4j.core.actors.Actor;
import actor4j.core.messages.ActorMessage;

public class FilterActor extends PipeActor {
	public FilterActor(BiFunction<Actor, ActorMessage<?>, ActorMessage<?>> filter, UUID next) {
		super(filter, next);
	}
	
	public FilterActor(String name, BiFunction<Actor, ActorMessage<?>, ActorMessage<?>> filter, UUID next) {
		super(name, filter, next);
	}
}