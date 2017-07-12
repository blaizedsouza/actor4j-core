/*
 * Copyright (c) 2015-2017, David A. Bauer. All rights reserved.
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */
package actor4j.core.supervisor;

import static actor4j.core.supervisor.SupervisorStrategyDirective.*;

import actor4j.core.exceptions.ActorInitializationException;
import actor4j.core.exceptions.ActorKilledException;

public class DefaultSupervisiorStrategy extends OneForOneSupervisorStrategy {
	public DefaultSupervisiorStrategy() {
		super(-1, Integer.MAX_VALUE);
	}

	@Override
	public SupervisorStrategyDirective apply(Exception e) {
		if (e instanceof ActorInitializationException || e instanceof ActorKilledException)
			return STOP;
		else
			return RESTART;
	}
}