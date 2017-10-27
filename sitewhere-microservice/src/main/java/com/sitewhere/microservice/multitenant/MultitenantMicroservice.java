/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.microservice.multitenant;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.curator.framework.CuratorFramework;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.common.collect.MapMaker;
import com.sitewhere.grpc.model.client.TenantManagementApiChannel;
import com.sitewhere.grpc.model.client.TenantManagementGrpcChannel;
import com.sitewhere.grpc.model.spi.client.ITenantManagementApiChannel;
import com.sitewhere.microservice.MicroserviceEnvironment;
import com.sitewhere.microservice.configuration.ConfigurableMicroservice;
import com.sitewhere.microservice.configuration.TenantPathInfo;
import com.sitewhere.microservice.multitenant.operations.BootstrapTenantEngineOperation;
import com.sitewhere.microservice.multitenant.operations.InitializeTenantEngineOperation;
import com.sitewhere.microservice.multitenant.operations.StartTenantEngineOperation;
import com.sitewhere.microservice.spi.multitenant.IMicroserviceTenantEngine;
import com.sitewhere.microservice.spi.multitenant.IMultitenantMicroservice;
import com.sitewhere.server.lifecycle.CompositeLifecycleStep;
import com.sitewhere.server.lifecycle.InitializeComponentLifecycleStep;
import com.sitewhere.server.lifecycle.StartComponentLifecycleStep;
import com.sitewhere.server.lifecycle.StopComponentLifecycleStep;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.server.lifecycle.ICompositeLifecycleStep;
import com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor;
import com.sitewhere.spi.tenant.ITenant;

/**
 * Microservice that contains engines for multiple tenants.
 * 
 * @author Derek
 */
public abstract class MultitenantMicroservice<T extends IMicroserviceTenantEngine> extends ConfigurableMicroservice
	implements IMultitenantMicroservice<T> {

    /** Max number of tenants being added/removed concurrently */
    private static final int MAX_CONCURRENT_TENANT_OPERATIONS = 5;

    /** Tenant management GRPC channel */
    private TenantManagementGrpcChannel tenantManagementGrpcChannel;

    /** Tenant management API channel */
    private ITenantManagementApiChannel tenantManagementApiChannel;

    /** Map of tenant engines indexed by tenant id */
    private ConcurrentMap<String, T> tenantEnginesByTenantId = new MapMaker().concurrencyLevel(4).makeMap();

    /** Map of tenants waiting for an engine to be created */
    private ConcurrentMap<String, ITenant> pendingEnginesByTenantId = new MapMaker().concurrencyLevel(4).makeMap();

    /** Map of tenants waiting for an engine to be created */
    private BlockingDeque<ITenant> enginesToCreate = new LinkedBlockingDeque<ITenant>();

    /** Executor for tenant operations */
    private ExecutorService tenantOperations;

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.configuration.ConfigurableMicroservice#
     * initialize(com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void initialize(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	super.initialize(monitor);

	// Create GRPC components.
	createGrpcComponents();

	// Handles threading for tenant operations.
	this.tenantOperations = Executors.newFixedThreadPool(MAX_CONCURRENT_TENANT_OPERATIONS,
		new TenantOperationsThreadFactory());
	tenantOperations.execute(new TenantEngineStarter());

	// Create step that will start components.
	ICompositeLifecycleStep init = new CompositeLifecycleStep("Initialize " + getName());

	// Initialize tenant management GRPC channel.
	init.addStep(new InitializeComponentLifecycleStep(this, getTenantManagementGrpcChannel(), true));

	// Execute initialization steps.
	init.execute(monitor);

	// Wait for microservice to be configured.
	waitForConfigurationReady();

	// Call logic for initializing microservice subclass.
	microserviceInitialize(monitor);
    }

    /**
     * Create components that interact via GRPC.
     */
    protected void createGrpcComponents() {
	this.tenantManagementGrpcChannel = new TenantManagementGrpcChannel(this,
		MicroserviceEnvironment.HOST_TENANT_MANAGEMENT, getInstanceSettings().getGrpcPort());
	this.tenantManagementApiChannel = new TenantManagementApiChannel(getTenantManagementGrpcChannel());
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.server.lifecycle.LifecycleComponent#start(com.sitewhere.spi
     * .server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void start(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	super.start(monitor);

	// Create step that will start components.
	ICompositeLifecycleStep start = new CompositeLifecycleStep("Start " + getName());

	// Start tenant mangement GRPC channel.
	start.addStep(new StartComponentLifecycleStep(this, getTenantManagementGrpcChannel(), true));

	// Execute startup steps.
	start.execute(monitor);

	// Initialize tenant engines.
	initializeTenantEngines(monitor);

	// Call logic for starting microservice subclass.
	microserviceStart(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.server.lifecycle.LifecycleComponent#stop(com.sitewhere.spi.
     * server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void stop(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	super.stop(monitor);

	// Call logic for stopping microservice subclass.
	microserviceStop(monitor);

	// Create step that will stop components.
	ICompositeLifecycleStep stop = new CompositeLifecycleStep("Stop " + getName());

	// Stop tenant management GRPC channel.
	stop.addStep(new StopComponentLifecycleStep(this, getTenantManagementGrpcChannel()));

	// Execute shutdown steps.
	stop.execute(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.configuration.ConfigurableMicroservice#
     * terminate(com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void terminate(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	// Shut down any tenant operations.
	if (tenantOperations != null) {
	    tenantOperations.shutdown();
	}
	getTenantManagementGrpcChannel().terminate(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.multitenant.IMultitenantMicroservice#
     * getTenantEngineByTenantId(java.lang.String)
     */
    @Override
    public T getTenantEngineByTenantId(String id) throws SiteWhereException {
	return getTenantEnginesByTenantId().get(id);
    }

    /**
     * Initialize tenant engines by inspecting the list of tenant
     * configurations, loading tenant information, then creating a tenant engine
     * for each.
     * 
     * @param monitor
     * @return
     * @throws SiteWhereException
     */
    protected void initializeTenantEngines(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	CuratorFramework curator = getZookeeperManager().getCurator();
	try {
	    if (curator.checkExists().forPath(getInstanceTenantsConfigurationPath()) != null) {
		List<String> tenantIds = curator.getChildren().forPath(getInstanceTenantsConfigurationPath());
		for (String tenantId : tenantIds) {
		    if (getTenantEngineByTenantId(tenantId) == null) {
			try {
			    queueTenantEngineInitialization(tenantId);
			} catch (SiteWhereException e) {
			    getLogger().error("Unable to add tenant engine for id '" + tenantId + "'.", e);
			}
		    }
		}
	    } else {
		getLogger().warn("No tenants currently configured.");
	    }
	} catch (Exception e) {
	    throw new SiteWhereException("Unable to create tenant engines.", e);
	}
    }

    /**
     * Queue initialization for tenant engine with the given tenant id.
     * 
     * @param tenantId
     * @return
     * @throws SiteWhereException
     */
    protected void queueTenantEngineInitialization(String tenantId) throws SiteWhereException {
	synchronized (getPendingEnginesByTenantId()) {
	    // Check for duplicate request.
	    if (getPendingEnginesByTenantId().get(tenantId) != null) {
		getLogger().debug("Skipping duplicate tenant initialization request.");
		return;
	    }

	    Authentication previous = SecurityContextHolder.getContext().getAuthentication();
	    try {
		// Use system user to look up tenant by id.
		SecurityContextHolder.getContext().setAuthentication(getSystemUser().getAuthentication());

		// Make sure API is available, then look up tenant.
		getTenantManagementApiChannel().waitForApiAvailable();
		ITenant tenant = getTenantManagementApiChannel().getTenantById(tenantId);
		if (tenant == null) {
		    throw new SiteWhereException("Unable to locate tenant by id '" + tenantId + "'.");
		}

		// Indicate engine is pending, then queue for processing.
		getPendingEnginesByTenantId().put(tenantId, tenant);
		if (!getEnginesToCreate().offer(tenant)) {
		    getLogger().error("No room on tenant initialization queue.");
		}
	    } finally {
		SecurityContextHolder.getContext().setAuthentication(previous);
	    }
	}
    }

    /**
     * Get the tenant engine responsible for handling configuration for the
     * given path.
     * 
     * @param pathInfo
     * @return
     * @throws SiteWhereException
     */
    protected IMicroserviceTenantEngine getTenantEngineForPathInfo(TenantPathInfo pathInfo) throws SiteWhereException {
	if (pathInfo != null) {
	    IMicroserviceTenantEngine engine = getTenantEngineByTenantId(pathInfo.getTenantId());
	    if (engine != null) {
		return engine;
	    } else {
		queueTenantEngineInitialization(pathInfo.getTenantId());
	    }
	}
	return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.configuration.IConfigurationListener#
     * onConfigurationAdded(java.lang.String, byte[])
     */
    @Override
    public void onConfigurationAdded(String path, byte[] data) {
	if (isConfigurationCacheReady()) {
	    try {
		TenantPathInfo pathInfo = TenantPathInfo.compute(path, this);
		IMicroserviceTenantEngine engine = getTenantEngineForPathInfo(pathInfo);
		if (engine != null) {
		    engine.onConfigurationAdded(pathInfo.getPath(), data);
		}
	    } catch (SiteWhereException e) {
		getLogger().error("Error processing configuration addition.", e);
	    }
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.configuration.IConfigurationListener#
     * onConfigurationUpdated(java.lang.String, byte[])
     */
    @Override
    public void onConfigurationUpdated(String path, byte[] data) {
	if (isConfigurationCacheReady()) {
	    try {
		TenantPathInfo pathInfo = TenantPathInfo.compute(path, this);
		IMicroserviceTenantEngine engine = getTenantEngineForPathInfo(pathInfo);
		if (engine != null) {
		    engine.onConfigurationUpdated(pathInfo.getPath(), data);
		}
	    } catch (SiteWhereException e) {
		getLogger().error("Error processing configuration update.", e);
	    }
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.microservice.spi.configuration.IConfigurationListener#
     * onConfigurationDeleted(java.lang.String)
     */
    @Override
    public void onConfigurationDeleted(String path) {
	if (isConfigurationCacheReady()) {
	    try {
		TenantPathInfo pathInfo = TenantPathInfo.compute(path, this);
		IMicroserviceTenantEngine engine = getTenantEngineForPathInfo(pathInfo);
		if (engine != null) {
		    engine.onConfigurationDeleted(pathInfo.getPath());
		}
	    } catch (SiteWhereException e) {
		getLogger().error("Error processing configuration delete.", e);
	    }
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * getConfigurationPaths()
     */
    @Override
    public String[] getConfigurationPaths() throws SiteWhereException {
	return new String[0];
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * microserviceInitialize(com.sitewhere.spi.server.lifecycle.
     * ILifecycleProgressMonitor)
     */
    @Override
    public void microserviceInitialize(ILifecycleProgressMonitor monitor) throws SiteWhereException {
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * microserviceStart(com.sitewhere.spi.server.lifecycle.
     * ILifecycleProgressMonitor)
     */
    @Override
    public void microserviceStart(ILifecycleProgressMonitor monitor) throws SiteWhereException {
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * microserviceStop(com.sitewhere.spi.server.lifecycle.
     * ILifecycleProgressMonitor)
     */
    @Override
    public void microserviceStop(ILifecycleProgressMonitor monitor) throws SiteWhereException {
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.microservice.spi.configuration.IConfigurableMicroservice#
     * initializeFromSpringContexts(org.springframework.context.
     * ApplicationContext, java.util.Map)
     */
    @Override
    public void initializeFromSpringContexts(ApplicationContext global, Map<String, ApplicationContext> contexts)
	    throws SiteWhereException {
    }

    public TenantManagementGrpcChannel getTenantManagementGrpcChannel() {
	return tenantManagementGrpcChannel;
    }

    public void setTenantManagementGrpcChannel(TenantManagementGrpcChannel tenantManagementGrpcChannel) {
	this.tenantManagementGrpcChannel = tenantManagementGrpcChannel;
    }

    public ITenantManagementApiChannel getTenantManagementApiChannel() {
	return tenantManagementApiChannel;
    }

    public void setTenantManagementApiChannel(ITenantManagementApiChannel tenantManagementApiChannel) {
	this.tenantManagementApiChannel = tenantManagementApiChannel;
    }

    public ConcurrentMap<String, T> getTenantEnginesByTenantId() {
	return tenantEnginesByTenantId;
    }

    public void setTenantEnginesByTenantId(ConcurrentMap<String, T> tenantEnginesByTenantId) {
	this.tenantEnginesByTenantId = tenantEnginesByTenantId;
    }

    public ConcurrentMap<String, ITenant> getPendingEnginesByTenantId() {
	return pendingEnginesByTenantId;
    }

    public void setPendingEnginesByTenantId(ConcurrentMap<String, ITenant> pendingEnginesByTenantId) {
	this.pendingEnginesByTenantId = pendingEnginesByTenantId;
    }

    public BlockingDeque<ITenant> getEnginesToCreate() {
	return enginesToCreate;
    }

    public void setEnginesToCreate(BlockingDeque<ITenant> enginesToCreate) {
	this.enginesToCreate = enginesToCreate;
    }

    public ExecutorService getTenantOperations() {
	return tenantOperations;
    }

    public void setTenantOperations(ExecutorService tenantOperations) {
	this.tenantOperations = tenantOperations;
    }

    /**
     * Processes the list of tenants waiting for tenant engines to be started.
     * 
     * @author Derek
     */
    private class TenantEngineStarter implements Runnable {

	@Override
	public void run() {
	    while (true) {
		try {
		    ITenant tenant = getEnginesToCreate().take();
		    if (getTenantEngineByTenantId(tenant.getId()) == null) {
			InitializeTenantEngineOperation
				.createCompletableFuture(MultitenantMicroservice.this, tenant, getTenantOperations())
				.thenCompose(engine -> StartTenantEngineOperation.createCompletableFuture(engine,
					getTenantOperations()))
				.thenCompose(engine -> BootstrapTenantEngineOperation.createCompletableFuture(engine,
					getTenantOperations()))
				.exceptionally(t -> {
				    getLogger().error("Unable to bootstrap tenant engine.", t);
				    return null;
				});
		    }
		} catch (SiteWhereException e) {
		    getLogger().warn("Exception processing tenant engine.", e);
		} catch (InterruptedException e) {
		    getLogger().warn("Tenant engine starter thread exiting.");
		    return;
		} catch (Throwable e) {
		    getLogger().warn("Unhandled exception processing tenant engine.", e);
		}
	    }
	}
    }

    /** Used for naming tenant operation threads */
    private class TenantOperationsThreadFactory implements ThreadFactory {

	/** Counts threads */
	private AtomicInteger counter = new AtomicInteger();

	public Thread newThread(Runnable r) {
	    return new Thread(r, "Tenant Operations " + counter.incrementAndGet());
	}
    }
}