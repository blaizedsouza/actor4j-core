/*
 * Copyright (c) 2015-2016, David A. Bauer
 */
package actor4j.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import actor4j.core.actors.Actor;
import actor4j.core.actors.PersistenceActor;
import actor4j.core.exceptions.ActorInitializationException;
import actor4j.core.exceptions.ActorKilledException;
import actor4j.core.messages.ActorMessage;
import actor4j.core.persistence.ActorPersistenceObject;
import actor4j.core.persistence.actor.PersistenceServiceActor;
import actor4j.core.protocols.RecoveryProtocol;
import actor4j.core.protocols.RestartProtocol;
import actor4j.core.protocols.StopProtocol;
import actor4j.core.supervisor.SupervisorStrategy;
import actor4j.core.utils.ActorFactory;
import actor4j.function.Consumer;
import actor4j.function.Function;
import tools4j.di.InjectorParam;

import static actor4j.core.protocols.ActorProtocolTag.*;
import static actor4j.core.utils.ActorLogger.logger;
import static actor4j.core.utils.ActorUtils.*;

public class ActorCell {
	static class PersistenceTuple {
		protected Consumer<ActorPersistenceObject> onSuccess;
		protected Consumer<Exception> onFailure;
		protected List<ActorPersistenceObject> objects;
		
		public PersistenceTuple(Consumer<ActorPersistenceObject> onSuccess, Consumer<Exception> onFailure, List<ActorPersistenceObject> objects) {
			super();
			this.onSuccess = onSuccess;
			this.onFailure = onFailure;
			this.objects = objects;
		}
	}
	
	protected ActorSystemImpl system;
	protected Actor actor;
	
	protected UUID id;
	
	protected UUID parent;
	protected Queue<UUID> children;
	
	protected Deque<Consumer<ActorMessage<?>>> behaviourStack;
	
	protected RestartProtocol restartProtocol;
	protected StopProtocol stopProtocol;
	protected RecoveryProtocol recoveryProtocol;
	
	protected Queue<UUID> deathWatcher;
	
	protected Function<ActorMessage<?>, Boolean> processedDirective;
	protected boolean activeDirectiveBehaviour;
	
	protected Queue<PersistenceTuple> persistenceTuples;
			
	public ActorCell(ActorSystemImpl system, Actor actor) {
		super();
		
		this.system = system;
		this.actor  = actor;
		
		this.id = UUID.randomUUID();
		
		children = new ConcurrentLinkedQueue<>();
		
		behaviourStack = new ArrayDeque<>();
		
		restartProtocol = new RestartProtocol(this);
		stopProtocol = new StopProtocol(this);
		recoveryProtocol = new RecoveryProtocol(this);
		
		deathWatcher =  new ConcurrentLinkedQueue<>();
		
		processedDirective = new Function<ActorMessage<?>, Boolean>() {
			@Override
			public Boolean apply(ActorMessage<?> message) {
				boolean result = false;
				
				if (isDirective(message) && !activeDirectiveBehaviour) {
					result = true;
					if (message.tag==INTERNAL_RESTART || message.tag==INTERNAL_STOP)
						activeDirectiveBehaviour = true;
						
					if (message.tag==INTERNAL_RESTART) {
						if (message.value instanceof Exception)
							preRestart((Exception)message.value);
						else
							preRestart(null);
					}
					else if (message.tag==INTERNAL_STOP)
						stop();
					else if (message.tag==INTERNAL_KILL) 
						throw new ActorKilledException();
					else if (message.tag==INTERNAL_RECOVER)
						recoveryProtocol.apply();
					else if (message.tag==INTERNAL_PERSISTENCE_RECOVER)
						recovery(message);
					else if (message.tag==INTERNAL_PERSISTENCE_SUCCESS) {
						PersistenceTuple tuple = persistenceTuples.poll();
						if (tuple.onSuccess!=null)
							for (int i=0; i<tuple.objects.size(); i++)
								tuple.onSuccess.accept(tuple.objects.get(i));
					}
					else if (message.tag==INTERNAL_PERSISTENCE_FAILURE) {
						PersistenceTuple tuple = persistenceTuples.poll();
						if (tuple.onFailure!=null)
							tuple.onFailure.accept((Exception)message.value);
					}
					else
						result = false;
				}
				
				return result;
			}	
		};
		
		persistenceTuples = new LinkedList<>();
	}
	
	public ActorSystemImpl getSystem() {
		return system;
	}
	
	public ActorSystem getSystemWrapper() {
		return system.wrapper;
	}
	
	public Actor getActor() {
		return actor;
	}
	
	public void setActor(Actor actor) {
		this.actor = actor;
	}

	public UUID getId() {
		return id;
	}
	
	public UUID getParent() {
		return parent;
	}
	
	public Queue<UUID> getChildren() {
		return children;
	}
	
	public void setActiveDirectiveBehaviour(boolean activeDirectiveBehaviour) {
		this.activeDirectiveBehaviour = activeDirectiveBehaviour;
	}

	public boolean isRoot() {
		return (parent==null);
	}
	
	public boolean isRootInUser() {
		return (parent==system.USER_ID);
	}
	
	public void internal_receive(ActorMessage<?> message) {
		if (!processedDirective.apply(message)) {
			Consumer<ActorMessage<?>> behaviour = behaviourStack.peek();
			if (behaviour==null)
				actor.receive(message);
			else
				behaviour.accept(message);	
		}
	}
	
	public void become(Consumer<ActorMessage<?>> behaviour, boolean replace) {
		if (replace && !behaviourStack.isEmpty())
			behaviourStack.pop();
		behaviourStack.push(behaviour);
	}
	
	public void become(Consumer<ActorMessage<?>> behaviour) {
		become(behaviour, true);
	}
	
	public void unbecome() {
		behaviourStack.pop();
	}
	
	public void unbecomeAll() {
		behaviourStack.clear();
	}
	
	public void send(ActorMessage<?> message) {
		system.messageDispatcher.post(message, id);
	}
	
	public void send(ActorMessage<?> message, String alias) {
		system.messageDispatcher.post(message, id, alias);
	}
	
	public void unhandled(ActorMessage<?> message) {
		if (system.debugUnhandled) {
			Actor sourceActor = system.cells.get(message.source).actor;
			if (sourceActor!=null)
				logger().warn(
					String.format("%s - System: actor (%s) - Unhandled message (%s) from source (%s)",
						system.name, actorLabel(actor), message.toString(), actorLabel(sourceActor)
					));
			else
				logger().warn(
					String.format("%s - System: actor (%s) - Unhandled message (%s) from unavaible source (???)",
						system.name, actorLabel(actor), message.toString()
					));
		}
	}
	
	protected UUID internal_addChild(ActorCell cell) {
		cell.parent = id;
		children.add(cell.id);
		system.internal_addCell(cell);
		system.messageDispatcher.registerCell(cell);
		/* preStart */
		cell.preStart();
		
		return cell.id;
	}
	
	public UUID addChild(Class<? extends Actor> clazz, Object... args) throws ActorInitializationException {
		InjectorParam[] params = new InjectorParam[args.length];
		for (int i=0; i<args.length; i++)
			params[i] = InjectorParam.createWithObj(args[i]);
		
		ActorCell cell = system.generateCell(clazz);
		system.container.registerConstructorInjector(cell.id, clazz, params);
		try {
			Actor child = (Actor)system.container.getInstance(cell.id);
			cell.actor = child;
		} catch (Exception e) {
			throw new ActorInitializationException();
		}
		
		return internal_addChild(cell);
	}
	
	public UUID addChild(ActorFactory factory) {
		ActorCell cell = system.generateCell(factory.create());
		system.container.registerFactoryInjector(cell.id, factory);
		
		return internal_addChild(cell);
	}
	
	public SupervisorStrategy supervisorStrategy() {
		return actor.supervisorStrategy();
	}
	
	public void preStart() {
		recoveryProtocol.apply();
		actor.preStart();
	}
	
	public void preRestart(Exception reason) {
		actor.preRestart(reason);
	}
	
	public void postRestart(Exception reason) {
		actor.postRestart(reason);
	}
	
	public void postStop() {
		actor.postStop();
	}
	
	public void restart(Exception reason) {
		restartProtocol.apply(reason);
	}
	
	public void stop() {
		stopProtocol.apply();
	}
	
	public void internal_stop() {
		if (parent!=null)
			system.cells.get(parent).children.remove(id);
		system.messageDispatcher.unregisterCell(this);
		system.removeActor(id);
		
		Iterator<UUID> iterator = deathWatcher.iterator();
		while (iterator.hasNext()) {
			UUID dest = iterator.next();
			system.sendAsDirective(new ActorMessage<>(null, INTERNAL_STOP_SUCCESS, id, dest));
		}
	}
	
	public void watch(UUID dest) {
		ActorCell cell = system.cells.get(dest);
		if (cell!=null)
			cell.deathWatcher.add(id);
	}
	
	public void unwatch(UUID dest) {
		ActorCell cell = system.cells.get(dest);
		if (cell!=null)
			cell.deathWatcher.remove(id);
	}
	
	@SuppressWarnings("unchecked")
	public <E extends ActorPersistenceObject> void persist(Consumer<E> onSuccess, Consumer<Exception> onFailure, E... events) {	
		if (system.persistenceMode && events!=null) {
			List<ActorPersistenceObject> list = new ArrayList<>(Arrays.asList(events));
			for (ActorPersistenceObject obj : list)
				obj.persistenceId = persistenceId();
			PersistenceTuple tuple = new PersistenceTuple((Consumer<ActorPersistenceObject>)onSuccess, onFailure, list);
			try {
				system.messageDispatcher.postPersistence(new ActorMessage<String>(new ObjectMapper().writeValueAsString(events), PersistenceServiceActor.EVENT, id, null));
				persistenceTuples.offer(tuple);
			} catch (JsonProcessingException e) {
				e.printStackTrace();
				onFailure.accept(e);
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	public <S extends ActorPersistenceObject> void saveSnapshot(Consumer<S> onSuccess, Consumer<Exception> onFailure, S state) {
		if (system.persistenceMode && state!=null) {
			state.persistenceId = persistenceId();
			List<ActorPersistenceObject> list = new ArrayList<>();
			list.add(state);
			PersistenceTuple tuple = new PersistenceTuple((Consumer<ActorPersistenceObject>)onSuccess, onFailure, list);
			try {
				system.messageDispatcher.postPersistence(new ActorMessage<String>(new ObjectMapper().writeValueAsString(state), PersistenceServiceActor.STATE, id, null));
				persistenceTuples.offer(tuple);
			} catch (JsonProcessingException e) {
				e.printStackTrace();
				onFailure.accept(e);
			}
		}
	}
	
	public void recovery(ActorMessage<?> message) {
		if (system.persistenceMode && actor instanceof PersistenceActor)
			((PersistenceActor<?, ?>)actor).recovery(message.valueAsString());
	}
	
	public UUID persistenceId() {
		UUID result = null;
		if (system.persistenceMode && actor instanceof PersistenceActor)
			result = ((PersistenceActor<?, ?>)actor).persistenceId();
		
		return result;
	}
}
