/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.mule.util.StringMessageUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;

import com.sitewhere.SiteWhere;
import com.sitewhere.configuration.TomcatConfigurationResolver;
import com.sitewhere.device.communication.DeviceCommandEventProcessor;
import com.sitewhere.device.event.processor.DefaultEventStorageProcessor;
import com.sitewhere.device.event.processor.DefaultInboundEventProcessorChain;
import com.sitewhere.device.event.processor.DefaultOutboundEventProcessorChain;
import com.sitewhere.device.event.processor.DeviceStreamProcessor;
import com.sitewhere.device.event.processor.OutboundProcessingStrategyDecorator;
import com.sitewhere.device.event.processor.RegistrationProcessor;
import com.sitewhere.rest.model.search.SearchCriteria;
import com.sitewhere.server.lifecycle.TenantLifecycleComponent;
import com.sitewhere.server.search.SearchProviderManager;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.asset.IAssetCategory;
import com.sitewhere.spi.asset.IAssetManagement;
import com.sitewhere.spi.asset.IAssetModuleManager;
import com.sitewhere.spi.configuration.IConfigurationResolver;
import com.sitewhere.spi.device.ICachingDeviceManagement;
import com.sitewhere.spi.device.IDeviceManagement;
import com.sitewhere.spi.device.IDeviceManagementCacheProvider;
import com.sitewhere.spi.device.ISite;
import com.sitewhere.spi.device.communication.IDeviceCommunication;
import com.sitewhere.spi.device.event.processor.IInboundEventProcessorChain;
import com.sitewhere.spi.device.event.processor.IOutboundEventProcessorChain;
import com.sitewhere.spi.search.ISearchResults;
import com.sitewhere.spi.search.external.ISearchProviderManager;
import com.sitewhere.spi.server.ISiteWhereTenantEngine;
import com.sitewhere.spi.server.asset.IAssetModelInitializer;
import com.sitewhere.spi.server.device.IDeviceModelInitializer;
import com.sitewhere.spi.server.lifecycle.LifecycleComponentType;
import com.sitewhere.spi.user.ITenant;

/**
 * Default implementation of {@link ISiteWhereTenantEngine} for managing processing and
 * data for a SiteWhere tenant.
 * 
 * @author Derek
 */
public class SiteWhereTenantEngine extends TenantLifecycleComponent implements ISiteWhereTenantEngine {

	/** Private logger instance */
	private static Logger LOGGER = Logger.getLogger(SiteWhereServer.class);

	/** Spring context for tenant */
	public static ApplicationContext TENANT_SPRING_CONTEXT;

	/** Allows Spring configuration to be resolved */
	private IConfigurationResolver configurationResolver = new TomcatConfigurationResolver();

	/** Device management cache provider implementation */
	private IDeviceManagementCacheProvider deviceManagementCacheProvider;

	/** Interface to device management implementation */
	private IDeviceManagement deviceManagement;

	/** Interface to asset management implementation */
	private IAssetManagement assetManagement;

	/** Interface to inbound event processor chain */
	private IInboundEventProcessorChain inboundEventProcessorChain;

	/** Interface to outbound event processor chain */
	private IOutboundEventProcessorChain outboundEventProcessorChain;

	/** Interface to device communication subsystem implementation */
	private IDeviceCommunication deviceCommunication;

	/** Interface for the asset module manager */
	private IAssetModuleManager assetModuleManager;

	/** Interface for the search provider manager */
	private ISearchProviderManager searchProviderManager;

	public SiteWhereTenantEngine(ITenant tenant) {
		super(LifecycleComponentType.TenantEngine);
		setTenant(tenant);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.lifecycle.ILifecycleComponent#start()
	 */
	@Override
	public void start() throws SiteWhereException {
		// Start asset management.
		startNestedComponent(getAssetManagement(), "Asset management startup failed.", true);

		// Start device management.
		startNestedComponent(getDeviceManagement(), "Device management startup failed.", true);

		// Start device management cache provider if specificed.
		if (getDeviceManagementCacheProvider() != null) {
			startNestedComponent(getDeviceManagementCacheProvider(),
					"Device management cache provider startup failed.", true);
		}

		// Populate asset data if requested.
		verifyAssetModel();

		// Start asset module manager.
		startNestedComponent(getAssetModuleManager(), "Asset module manager startup failed.", true);

		// Start search provider manager.
		startNestedComponent(getSearchProviderManager(), "Search provider manager startup failed.", true);
		verifyDeviceModel();

		// Enable outbound processor chain.
		if (getOutboundEventProcessorChain() != null) {
			startNestedComponent(getOutboundEventProcessorChain(),
					"Outbound processor chain startup failed.", true);
			getOutboundEventProcessorChain().setProcessingEnabled(true);
		}

		// Enable inbound processor chain.
		if (getInboundEventProcessorChain() != null) {
			startNestedComponent(getInboundEventProcessorChain(), "Inbound processor chain startup failed.",
					true);
		}

		// Start device communication subsystem.
		startNestedComponent(getDeviceCommunication(), "Device communication subsystem startup failed.", true);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.lifecycle.ILifecycleComponent#stop()
	 */
	@Override
	public void stop() throws SiteWhereException {
		// Disable device communications.
		getDeviceCommunication().lifecycleStop();
		getInboundEventProcessorChain().lifecycleStop();
		getOutboundEventProcessorChain().setProcessingEnabled(false);
		getOutboundEventProcessorChain().lifecycleStop();

		// Stop core management implementations.
		if (getDeviceManagementCacheProvider() != null) {
			getDeviceManagementCacheProvider().lifecycleStop();
		}
		getDeviceManagement().lifecycleStop();
		getAssetModuleManager().lifecycleStop();
		getAssetManagement().lifecycleStop();
		getSearchProviderManager().lifecycleStop();
	}

	/**
	 * Initialize components from Spring beans.
	 * 
	 * @throws SiteWhereException
	 */
	public void initialize() throws SiteWhereException {
		// Initialize the tenant Spring context.
		initializeSpringContext();

		// Initialize device communication subsystem.
		initializeDeviceCommunicationSubsystem();

		// Initialize device management.
		initializeDeviceManagement();

		// Initialize processing chain for inbound events.
		initializeInboundEventProcessorChain();

		// Initialize asset management.
		initializeAssetManagement();

		// Initialize search provider management.
		initializeSearchProviderManagement();
	}

	/**
	 * Verifies and loads the Spring configuration file.
	 * 
	 * @throws SiteWhereException
	 */
	protected void initializeSpringContext() throws SiteWhereException {
		TENANT_SPRING_CONTEXT =
				getConfigurationResolver().resolveTenantContext(getTenant(),
						SiteWhere.getServer().getVersion());
	}

	/**
	 * Initialize device management implementation and associated decorators.
	 * 
	 * @throws SiteWhereException
	 */
	protected void initializeDeviceManagement() throws SiteWhereException {
		// Load device management cache provider if configured.
		try {
			this.deviceManagementCacheProvider =
					(IDeviceManagementCacheProvider) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_DEVICE_MANAGEMENT_CACHE_PROVIDER);
			LOGGER.info("Device management cache provider using: "
					+ deviceManagementCacheProvider.getClass().getName());
		} catch (NoSuchBeanDefinitionException e) {
			LOGGER.info("No device management cache provider configured. Caching disabled.");
		}

		// Verify that a device management implementation exists.
		try {
			IDeviceManagement deviceManagementImpl =
					(IDeviceManagement) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_DEVICE_MANAGEMENT);
			this.deviceManagement = configureDeviceManagement(deviceManagementImpl);
			LOGGER.info("Device management implementation using: "
					+ deviceManagementImpl.getClass().getName());

		} catch (NoSuchBeanDefinitionException e) {
			throw new SiteWhereException("No device management implementation configured.");
		}
	}

	/**
	 * Configure device management implementation by injecting configured options or
	 * wrapping to add functionality.
	 * 
	 * @param wrapped
	 * @return
	 * @throws SiteWhereException
	 */
	protected IDeviceManagement configureDeviceManagement(IDeviceManagement management)
			throws SiteWhereException {

		// Inject cache provider if available.
		if (getDeviceManagementCacheProvider() != null) {
			if (management instanceof ICachingDeviceManagement) {
				((ICachingDeviceManagement) management).setCacheProvider(getDeviceManagementCacheProvider());
				LOGGER.info("Device management implementation is using configured cache provider.");
			} else {
				LOGGER.info("Device management implementation not using cache provider.");
			}
		}

		try {
			// If outbound device event processor chain is defined, use it.
			outboundEventProcessorChain =
					(IOutboundEventProcessorChain) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_OUTBOUND_PROCESSOR_CHAIN);
			management = new OutboundProcessingStrategyDecorator(management);
			LOGGER.info("Event processor chain found with "
					+ outboundEventProcessorChain.getProcessors().size() + " processors.");
		} catch (NoSuchBeanDefinitionException e) {
			// If no processor chain is defined, use a default chain that supports core
			// system functionality.
			LOGGER.info("No outbound event processor chain found. Using defaults.");
			outboundEventProcessorChain = new DefaultOutboundEventProcessorChain();
			outboundEventProcessorChain.getProcessors().add(new DeviceCommandEventProcessor());
		}

		return management;
	}

	/**
	 * Initializes the {@link IInboundEventProcessorChain} that handles events coming into
	 * the system from external devices.
	 * 
	 * @throws SiteWhereException
	 */
	protected void initializeInboundEventProcessorChain() throws SiteWhereException {
		try {
			// If inbound device event processor chain is defined, use it.
			inboundEventProcessorChain =
					(IInboundEventProcessorChain) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_INBOUND_PROCESSOR_CHAIN);
		} catch (NoSuchBeanDefinitionException e) {
			// If no processor chain is defined, use a default chain that supports core
			// system functionality.
			LOGGER.info("No inbound event processor chain found. Using defaults.");
			inboundEventProcessorChain = new DefaultInboundEventProcessorChain();
			inboundEventProcessorChain.getProcessors().add(new DefaultEventStorageProcessor());
			inboundEventProcessorChain.getProcessors().add(new RegistrationProcessor());
			inboundEventProcessorChain.getProcessors().add(new DeviceStreamProcessor());
		}
	}

	/**
	 * Verify and initialize device communication subsystem implementation.
	 * 
	 * @throws SiteWhereException
	 */
	protected void initializeDeviceCommunicationSubsystem() throws SiteWhereException {
		try {
			deviceCommunication =
					(IDeviceCommunication) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_DEVICE_COMMUNICATION);
		} catch (NoSuchBeanDefinitionException e) {
			throw new SiteWhereException("No device communication subsystem implementation configured.");
		}
	}

	/**
	 * Verify and initialize asset module manager.
	 * 
	 * @throws SiteWhereException
	 */
	protected void initializeAssetManagement() throws SiteWhereException {
		try {
			assetManagement =
					(IAssetManagement) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_ASSET_MANAGEMENT);
			assetModuleManager =
					(IAssetModuleManager) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_ASSET_MODULE_MANAGER);
		} catch (NoSuchBeanDefinitionException e) {
			throw new SiteWhereException("No asset module manager implementation configured.");
		}
	}

	/**
	 * Verify and initialize search provider manager.
	 * 
	 * @throws SiteWhereException
	 */
	protected void initializeSearchProviderManagement() throws SiteWhereException {
		try {
			searchProviderManager =
					(ISearchProviderManager) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_SEARCH_PROVIDER_MANAGER);
		} catch (NoSuchBeanDefinitionException e) {
			searchProviderManager = new SearchProviderManager();
		}
	}

	/**
	 * Read a line from standard in.
	 * 
	 * @return
	 * @throws SiteWhereException
	 */
	protected String readLine() throws SiteWhereException {
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
			return br.readLine();
		} catch (IOException e) {
			throw new SiteWhereException(e);
		}
	}

	/**
	 * Check whether device model is populated and offer to bootstrap system if not.
	 */
	protected void verifyDeviceModel() {
		try {
			IDeviceModelInitializer init =
					(IDeviceModelInitializer) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_DEVICE_MODEL_INITIALIZER);
			ISearchResults<ISite> sites = getDeviceManagement().listSites(new SearchCriteria(1, 1));
			if (sites.getNumResults() == 0) {
				List<String> messages = new ArrayList<String>();
				messages.add("There are currently no sites defined in the system. You have the option of loading "
						+ "a default dataset for previewing system functionality. Would you like to load the default "
						+ "dataset?");
				String message = StringMessageUtils.getBoilerPlate(messages, '*', 60);
				LOGGER.info("\n" + message + "\n");
				System.out.println("Load default dataset? Yes/No (Default is Yes)");
				String response = readLine();
				if ((response == null) && (init.isInitializeIfNoConsole())) {
					response = "Y";
				} else if ((response == null) && (!init.isInitializeIfNoConsole())) {
					response = "N";
				}
				if ((response.length() == 0) || (response.toLowerCase().startsWith("y"))) {
					init.initialize(getDeviceManagement(), getAssetModuleManager());
				}
			}
		} catch (NoSuchBeanDefinitionException e) {
			LOGGER.info("No device model initializer found in Spring bean configuration. Skipping.");
			return;
		} catch (SiteWhereException e) {
			LOGGER.warn("Unable to read from device model.", e);
		}
	}

	/**
	 * Check whether asset model is populated and offer to bootstrap system if not.
	 */
	protected void verifyAssetModel() {
		try {
			IAssetModelInitializer init =
					(IAssetModelInitializer) TENANT_SPRING_CONTEXT.getBean(SiteWhereServerBeans.BEAN_ASSET_MODEL_INITIALIZER);
			ISearchResults<IAssetCategory> categories =
					getAssetManagement().listAssetCategories(new SearchCriteria(1, 1));
			if (categories.getNumResults() == 0) {
				List<String> messages = new ArrayList<String>();
				messages.add("There are currently no asset categories defined in the system. You have the option of "
						+ "loading a default dataset for previewing system functionality. Would you like to load the "
						+ "default asset dataset?");
				String message = StringMessageUtils.getBoilerPlate(messages, '*', 60);
				LOGGER.info("\n" + message + "\n");
				System.out.println("Load default assets? Yes/No (Default is Yes)");
				String response = readLine();
				if ((response == null) && (init.isInitializeIfNoConsole())) {
					response = "Y";
				} else if ((response == null) && (!init.isInitializeIfNoConsole())) {
					response = "N";
				}
				if ((response.length() == 0) || (response.toLowerCase().startsWith("y"))) {
					init.initialize(getAssetManagement());
				}
			}
		} catch (NoSuchBeanDefinitionException e) {
			LOGGER.info("No asset model initializer found in Spring bean configuration. Skipping.");
			return;
		} catch (SiteWhereException e) {
			LOGGER.warn("Unable to read from asset model.", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.lifecycle.ILifecycleComponent#getLogger()
	 */
	@Override
	public Logger getLogger() {
		return LOGGER;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.server.lifecycle.LifecycleComponent#getComponentName()
	 */
	@Override
	public String getComponentName() {
		return "Tenant Engine: " + getTenant().getName();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.ISiteWhereTenantEngine#getConfigurationResolver()
	 */
	public IConfigurationResolver getConfigurationResolver() {
		return configurationResolver;
	}

	public void setConfigurationResolver(IConfigurationResolver configurationResolver) {
		this.configurationResolver = configurationResolver;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.sitewhere.spi.server.ISiteWhereTenantEngine#getDeviceManagementCacheProvider()
	 */
	public IDeviceManagementCacheProvider getDeviceManagementCacheProvider() {
		return deviceManagementCacheProvider;
	}

	public void setDeviceManagementCacheProvider(IDeviceManagementCacheProvider deviceManagementCacheProvider) {
		this.deviceManagementCacheProvider = deviceManagementCacheProvider;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.ISiteWhereTenantEngine#getDeviceManagement()
	 */
	public IDeviceManagement getDeviceManagement() {
		return deviceManagement;
	}

	public void setDeviceManagement(IDeviceManagement deviceManagement) {
		this.deviceManagement = deviceManagement;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.ISiteWhereTenantEngine#getAssetManagement()
	 */
	public IAssetManagement getAssetManagement() {
		return assetManagement;
	}

	public void setAssetManagement(IAssetManagement assetManagement) {
		this.assetManagement = assetManagement;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.sitewhere.spi.server.ISiteWhereTenantEngine#getInboundEventProcessorChain()
	 */
	public IInboundEventProcessorChain getInboundEventProcessorChain() {
		return inboundEventProcessorChain;
	}

	public void setInboundEventProcessorChain(IInboundEventProcessorChain inboundEventProcessorChain) {
		this.inboundEventProcessorChain = inboundEventProcessorChain;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.sitewhere.spi.server.ISiteWhereTenantEngine#getOutboundEventProcessorChain()
	 */
	public IOutboundEventProcessorChain getOutboundEventProcessorChain() {
		return outboundEventProcessorChain;
	}

	public void setOutboundEventProcessorChain(IOutboundEventProcessorChain outboundEventProcessorChain) {
		this.outboundEventProcessorChain = outboundEventProcessorChain;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.ISiteWhereTenantEngine#getDeviceCommunication()
	 */
	public IDeviceCommunication getDeviceCommunication() {
		return deviceCommunication;
	}

	public void setDeviceCommunication(IDeviceCommunication deviceCommunication) {
		this.deviceCommunication = deviceCommunication;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.ISiteWhereTenantEngine#getAssetModuleManager()
	 */
	public IAssetModuleManager getAssetModuleManager() {
		return assetModuleManager;
	}

	public void setAssetModuleManager(IAssetModuleManager assetModuleManager) {
		this.assetModuleManager = assetModuleManager;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.sitewhere.spi.server.ISiteWhereTenantEngine#getSearchProviderManager()
	 */
	public ISearchProviderManager getSearchProviderManager() {
		return searchProviderManager;
	}

	public void setSearchProviderManager(ISearchProviderManager searchProviderManager) {
		this.searchProviderManager = searchProviderManager;
	}
}