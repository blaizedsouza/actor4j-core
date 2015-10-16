/*
 * Copyright (c) 2015, David A. Bauer
 */
package actor4j.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import safety4j.SafetyManager;
import tools4j.di.DIContainer;
import tools4j.di.InjectorParam;

import static actor4j.core.ActorUtils.*;
import static actor4j.core.ActorProtocolTag.*;

public class ActorSystem {
	protected String name;
	
	protected DIContainer<UUID> container;
	
	protected Map<UUID, Actor> actors; // ActorID -> Actor
	protected Map<String, UUID> aliases; // ActorAlias -> ActorID
	protected Map<UUID, String> hasAliases;
	protected Map<UUID, Boolean> resourceActors;
	protected ActorMessageDispatcher messageDispatcher;
	
	protected int parallelismMin;
	protected int parallelismFactor;
	
	protected boolean softMode; // hard, soft
	protected long softSleep;
	
	protected boolean debugUnhandled;
	
	protected Queue<ActorMessage<?>> bufferQueue;
	protected ActorExecuterService executerService;
	
	protected ActorBalancingOnCreation actorBalancingOnCreation;
	protected ActorBalancingOnRuntime actorBalancingOnRuntime;
	
	protected ActorStrategyOnFailure actorStrategyOnFailure;
	
	protected List<String> serverURIs;
	protected boolean clientMode;
	protected ActorClientRunnable clientRunnable;
	
	protected AtomicBoolean analyzeMode;
	protected ActorAnalyzerThread analyzerThread;
	
	protected CountDownLatch countDownLatch;
	
	public final UUID USER_ID;
	public final UUID SYSTEM_ID;
	public final UUID UNKNOWN_ID;
	
	protected Actor user;
	
	public ActorSystem() {
		this(null);
	}
	
	public ActorSystem(String name) {
		super();
		
		if (name!=null)
			this.name = name;
		else
			this.name = "actor4j";
		
		container      = DIContainer.create();
		
		actors         = new ConcurrentHashMap<>();
		aliases        = new ConcurrentHashMap<>();
		hasAliases     = new ConcurrentHashMap<>();
		resourceActors = new ConcurrentHashMap<>();
		messageDispatcher = new ActorMessageDispatcher(this);
		
		setParallelismMin(0);
		parallelismFactor = 1;
		
		softMode  = true;
		softSleep = 25;
		
		bufferQueue = new ConcurrentLinkedQueue<>();
		executerService = new ActorExecuterService(this);
		
		actorBalancingOnCreation = new ActorBalancingOnCreation(this);
		actorBalancingOnRuntime = new ActorBalancingOnRuntime(this);
		
		actorStrategyOnFailure = new ActorStrategyOnFailure(this);
		
		serverURIs = new ArrayList<>();
		
		analyzeMode = new AtomicBoolean(false);
		
		countDownLatch = new CountDownLatch(1);
		
		user = new Actor("user") {
			@Override
			public void receive(ActorMessage<?> message) {
				// empty
			}
			
			@Override
			public void postStop() {
				countDownLatch.countDown();
			}
		};
		USER_ID = system_addActor(user);
		SYSTEM_ID = system_addActor(new Actor("system") {
			@Override
			protected void receive(ActorMessage<?> message) {
				// empty
			}
		});
		UNKNOWN_ID = system_addActor(new Actor("unknown") {
			@Override
			protected void receive(ActorMessage<?> message) {
				// empty
			}
		});
	}
	
	public String getName() {
		return name;
	}
	
	public ActorSystem setClientRunnable(ActorClientRunnable clientRunnable) {
		clientMode = (clientRunnable!=null);
			
		this.clientRunnable = clientRunnable;
		
		return this;
	}
	
	public ActorSystem analyze(ActorAnalyzerThread analyzerThread) {
		if (!executerService.isStarted()) {
			this.analyzerThread = analyzerThread;
			if (analyzerThread!=null) {
				analyzerThread.setSystem(this);
				analyzeMode.set(true);
			}
		}
		
		return this;
	}
	
	public int getParallelismMin() {
		return parallelismMin;
	}
	
	public ActorSystem setParallelismMin(int parallelismMin) {
		if (parallelismMin<=0)
			this.parallelismMin = Runtime.getRuntime().availableProcessors();
		else
			this.parallelismMin = parallelismMin;
		
		return this;
	}

	public int getParallelismFactor() {
		return parallelismFactor;
	}
	
	public ActorSystem setParallelismFactor(int parallelismFactor) {
		this.parallelismFactor = parallelismFactor;
		
		return this;
	}

	public void setSoftMode(boolean softMode, long softSleep) {
		this.softMode = softMode;
		this.softSleep = softSleep;
	}

	public ActorSystem softMode() {
		this.softMode = true;
		
		return this;
	}
	
	public ActorSystem hardMode() {
		this.softMode = false;
		
		return this;
	}
	
	public ActorSystem setDebugUnhandled(boolean debugUnhandled) {
		this.debugUnhandled = debugUnhandled;
		
		return this;
	}
		
	public ActorSystem addURI(String uri) {
		serverURIs.add(uri);
		
		return this;
	}
	
	protected UUID system_addActor(Actor actor) {
		actor.setSystem(this);
		actors.put(actor.getId(), actor);
		if (actor instanceof ResourceActor)
			resourceActors.put(actor.getId(), true);
		return actor.getId();
	}
	
	protected UUID user_addActor(Actor actor) {
		actor.parent = USER_ID;
		user.children.add(actor.getId());
		return system_addActor(actor);
	}
	
	public UUID addActor(Class<? extends Actor> clazz, Object... args) throws ActorInitializationException {
		InjectorParam[] params = new InjectorParam[args.length];
		for (int i=0; i<args.length; i++)
			params[i] = InjectorParam.createWithObj(args[i]);
		
		UUID temp = UUID.randomUUID();
		container.registerConstructorInjector(temp, clazz, params);
		
		Actor actor = null;
		try {
			actor = (Actor)container.getInstance(temp);
			container.registerConstructorInjector(actor.getId(), clazz, params);
			container.unregister(temp);
		} catch (Exception e) {
			SafetyManager.getInstance().notifyErrorHandler(new ActorInitializationException(), "initialization", null);
		}
		
		return (actor!=null) ? user_addActor(actor) : UUID_ZERO;
	}
	
	public UUID addActor(ActorFactory factory) {
		Actor actor = factory.create();
		container.registerFactoryInjector(actor.getId(), factory);
		
		return user_addActor(actor);
	}
	
	protected void removeActor(UUID id) {
		actors.remove(id);
		String alias = null;
		if ((alias=hasAliases.get(id))!=null) {
			hasAliases.remove(id);
			aliases.remove(alias);
		}
	}
	
	public boolean hasActor(String uuid) {
		UUID key;
		try {
			key = UUID.fromString(uuid);
		}
		catch (IllegalArgumentException e) {
			return false;
		}
		return actors.containsKey(key);
	}
	
	public ActorSystem setAlias(UUID id, String alias) {
		if (!hasAliases.containsKey(id)) {
			aliases.put(alias, id);
			hasAliases.put(id, alias);
		}	
		else {
			aliases.remove(hasAliases.get(id));
			aliases.put(alias, id);
			hasAliases.put(id, alias);
		}
		
		return this;
	}
	
	public UUID getActor(String alias) {
		return aliases.get(alias);
	}
	
	public ActorSystem send(ActorMessage<?> message) {
		if (!executerService.isStarted()) 
			bufferQueue.offer(message.copy());
		else
			messageDispatcher.postOuter(message);
		
		return this;
	}
	
	public void sendAsServer(ActorMessage<?> message) {
		if (!executerService.isStarted()) 
			bufferQueue.offer(message.copy());
		else
			messageDispatcher.postServer(message);
	}
	
	public void sendAsDirective(ActorMessage<?> message) {
		if (executerService.isStarted()) 
			messageDispatcher.postDirective(message);
	}
	
	public ActorSystem broadcast(ActorMessage<?> message, ActorGroup group) {
		if (!executerService.isStarted())
			for (UUID id : group) {
				message.dest = id;
				bufferQueue.offer(message.copy());
			}
		else
			for (UUID id : group) {
				message.dest = id;
				messageDispatcher.postOuter(message);
			}
		
		return this;
	}
	
	public ActorTimer timer() {
		return executerService.actorTimer;
	}
	
	public void start() {
		start(null);
	}
	
	public void start(Runnable onTermination) {
		if (!executerService.isStarted())
			executerService.start(new Runnable() {
				@Override
				public void run() {
					if (analyzeMode.get())
						analyzerThread.start();
					
					/* preStart */
					for (Actor actor : actors.values())
						actor.preStart();
					
					ActorMessage<?> message = null;
					while ((message=bufferQueue.poll())!=null)
						messageDispatcher.postOuter(message);
				}
			}, onTermination);
	}
	
	public void shutdownWithActors() {
		shutdownWithActors(false);
	}
	
	public void shutdownWithActors(final boolean await) {
		if (executerService.isStarted()) {
			if (analyzeMode.get()) {
				analyzeMode.set(false);
				analyzerThread.interrupt();
			}
			
			Thread waitOnTermination = new Thread(new Runnable() {
				@Override
				public void run() {
					send(new ActorMessage<>(null, INTERNAL_STOP, USER_ID, USER_ID));
					try {
						countDownLatch.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					executerService.shutdown(await);
				}
			});
			
			waitOnTermination.start();
			if (await)
				try {
					waitOnTermination.join();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
		}
	}
	
	public void shutdown() {
		shutdown(false);
	}
	
	public void shutdown(boolean await) {
		if (executerService.isStarted()) {
			if (analyzeMode.get()) {
				analyzeMode.set(false);
				analyzerThread.interrupt();
			}
			executerService.shutdown(await);
		}
	}
	
	public ActorExecuterService getExecuterService() {
		return executerService;
	}
	
	public List<String> getServerURIs() {
		return serverURIs;
	}
}
