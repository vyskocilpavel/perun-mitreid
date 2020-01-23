package cz.muni.ics.oidc.server.filters.impl;

import com.google.common.base.Strings;
import cz.muni.ics.oidc.BeanUtil;
import cz.muni.ics.oidc.server.PerunPrincipal;
import cz.muni.ics.oidc.server.configurations.PerunOidcConfig;
import cz.muni.ics.oidc.server.connectors.PerunConnector;
import cz.muni.ics.oidc.server.filters.FiltersUtils;
import cz.muni.ics.oidc.server.filters.PerunFilterConstants;
import cz.muni.ics.oidc.server.filters.PerunRequestFilter;
import cz.muni.ics.oidc.server.filters.PerunRequestFilterParams;
import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.provider.OAuth2RequestFactory;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

import static cz.muni.ics.oidc.server.filters.PerunFilterConstants.CLIENT_ID;


/**
 * Filter for collecting data about login.
 *
 * Configuration (replace "name" part with name defined for filter):
 * - filter.name.idpNameAttributeName - Mapping to Request attribute containing name of used Identity Provider
 * - filter.name.idpEntityIdAttributeName - Mapping to Request attribute containing entity_id of used Identity Provider
 * - filter.name.statisticsTableName - Name of the table where to store data
 * 		(depends on DataSource bean mitreIdStats)
 * - filter.name.identityProvidersMapTableName - Name of the table with mapping of entity_id (IDP) to idp name
 * 		(depends on DataSource bean mitreIdStats)
 * - filter.name.serviceProvidersMapTableName - Name of the table with mapping of client_id (SP) to client name
 * 		(depends on DataSource bean mitreIdStats)
 * - filter.name.detailedStatisticsEnabled - TRUE if detailed statistics should be logged, FALSE otherwise (by default)
 * - filter.name.detailedStatisticsTableName - Name of the table where we store detailed statistics
 * 		(depends on DataSource bean mitreIdStats)
 *
 * @author Dominik Baránek <0Baranek.dominik0@gmail.com>
 */
public class ProxyStatisticsFilter extends PerunRequestFilter {

	private final static Logger log = LoggerFactory.getLogger(ProxyStatisticsFilter.class);

	/* CONFIGURATION OPTIONS */
	private static final String IDP_NAME_ATTRIBUTE_NAME = "idpNameAttributeName";
	private static final String IDP_ENTITY_ID_ATTRIBUTE_NAME = "idpEntityIdAttributeName";
	private static final String STATISTICS_TABLE_NAME = "statisticsTableName";
	private static final String IDENTITY_PROVIDERS_MAP_TABLE_NAME = "identityProvidersMapTableName";
	private static final String SERVICE_PROVIDERS_MAP_TABLE_NAME = "serviceProvidersMapTableName";
	private static final String DETAILED_STATISTICS_TABLE_NAME = "detailedStatisticsTableName";
	private static final String DETAILED_STATISTICS_ENABLED = "detailedStatisticsEnabled";

	private final String idpNameAttributeName;
	private final String idpEntityIdAttributeName;
	private final String statisticsTableName;
	private final String identityProvidersMapTableName;
	private final String serviceProvidersMapTableName;
	private final String detailedStatisticsTableName;
	private final boolean detailedStatisticsEnabled;
	/* END OF CONFIGURATION OPTIONS */

	private final RequestMatcher requestMatcher = new AntPathRequestMatcher(PerunFilterConstants.AUTHORIZE_REQ_PATTERN);

	private final OAuth2RequestFactory authRequestFactory;
	private final ClientDetailsEntityService clientService;
	private final DataSource mitreIdStats;
	private final PerunOidcConfig config;

	public ProxyStatisticsFilter(PerunRequestFilterParams params) {
		super(params);

		BeanUtil beanUtil = params.getBeanUtil();

		this.authRequestFactory = beanUtil.getBean(OAuth2RequestFactory.class);
		this.clientService = beanUtil.getBean(ClientDetailsEntityService.class);
		this.mitreIdStats = beanUtil.getBean("mitreIdStats", DataSource.class);
		this.config = beanUtil.getBean(PerunOidcConfig.class);

		this.idpNameAttributeName = params.getProperty(IDP_NAME_ATTRIBUTE_NAME);
		this.idpEntityIdAttributeName = params.getProperty(IDP_ENTITY_ID_ATTRIBUTE_NAME);
		this.statisticsTableName = params.getProperty(STATISTICS_TABLE_NAME);
		this.identityProvidersMapTableName = params.getProperty(IDENTITY_PROVIDERS_MAP_TABLE_NAME);
		this.serviceProvidersMapTableName = params.getProperty(SERVICE_PROVIDERS_MAP_TABLE_NAME);
		this.detailedStatisticsTableName = params.getProperty(DETAILED_STATISTICS_TABLE_NAME);
		if (params.hasProperty(DETAILED_STATISTICS_ENABLED)) {
			this.detailedStatisticsEnabled = Boolean.parseBoolean(params.getProperty(DETAILED_STATISTICS_ENABLED));
		} else {
			this.detailedStatisticsEnabled = false;
		}
	}

	@Override
	protected boolean process(ServletRequest req, ServletResponse res) {
		HttpServletRequest request = (HttpServletRequest) req;

		ClientDetailsEntity client = FiltersUtils.extractClient(requestMatcher, request, authRequestFactory, clientService);
		if (client == null) {
			log.debug("Could not fetch client, skip to next filter");
			return true;
		}

		String clientIdentifier = client.getClientId();
		String clientName = client.getClientName();

		if (Strings.isNullOrEmpty((String) request.getAttribute(idpEntityIdAttributeName))) {
			log.warn("Attribute '" + idpEntityIdAttributeName + "' is null or empty, skip to next filter");
			return true;
		}

		String idpEntityIdFromRequest = (String) request.getAttribute(idpEntityIdAttributeName);
		String idpNameFromRequest = (String) request.getAttribute(idpNameAttributeName);

		String idpEntityId = changeEncodingOfParam(idpEntityIdFromRequest,
				StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8);
		String idpName = changeEncodingOfParam(idpNameFromRequest,
				StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8);


		String userId = request.getUserPrincipal().getName();
		insertLogin(idpEntityId, idpName, clientIdentifier, clientName, userId);

		logUserLogin(request);

		return true;
	}

	private void insertLogin(String idpEntityId, String idpName, String spIdentifier, String spName) {
		LocalDate date = LocalDate.now();

		String queryStats = "INSERT INTO " + statisticsTableName + "(year, month, day, sourceIdp, service, count)" +
				" VALUES(?,?,?,?,?,'1') ON DUPLICATE KEY UPDATE count = count + 1";

		String queryIdPMap = "INSERT INTO " + identityProvidersMapTableName + "(entityId, name)" +
				" VALUES (?, ?) ON DUPLICATE KEY UPDATE name = ?";

		String queryServiceMap = "INSERT INTO " + serviceProvidersMapTableName + "(identifier, name)" +
				" VALUES (?, ?) ON DUPLICATE KEY UPDATE name = ?";

		try (Connection c = mitreIdStats.getConnection()) {
			try (PreparedStatement preparedStatement = c.prepareStatement(queryStats)) {
				preparedStatement.setInt(1, date.getYear());
				preparedStatement.setInt(2, date.getMonthValue());
				preparedStatement.setInt(3, date.getDayOfMonth());
				preparedStatement.setString(4, idpEntityId);
				preparedStatement.setString(5, spIdentifier);
				preparedStatement.execute();
			}
			if (!Strings.isNullOrEmpty(idpName)) {
				try (PreparedStatement preparedStatement = c.prepareStatement(queryIdPMap)) {
					preparedStatement.setString(1, idpEntityId);
					preparedStatement.setString(2, idpName);
					preparedStatement.setString(3, idpName);
					preparedStatement.execute();
				}
			}
			if (!Strings.isNullOrEmpty(spName)) {
				try (PreparedStatement preparedStatement = c.prepareStatement(queryServiceMap)) {
					preparedStatement.setString(1, spIdentifier);
					preparedStatement.setString(2, spName);
					preparedStatement.setString(3, spName);
					preparedStatement.execute();
				}
			}
			log.debug("The login log was successfully stored into database: ({},{},{},{})", idpEntityId, idpName, spIdentifier, spName);
		} catch (SQLException ex) {
			log.warn("Statistics weren't updated due to SQLException.");
			log.error("Caught SQLException", ex);
		}
	}

	private void insertLogin(String idpEntityId, String idpName, String spIdentifier, String spName, String userId) {
		insertLogin(idpEntityId, idpName, spIdentifier, spName);

		if (detailedStatisticsEnabled && userId != null && !userId.trim().isEmpty()) {
			String detailedStats = "INSERT INTO " + detailedStatisticsTableName + "(year, month, day, sourceIdp, service, user, count)" +
					" VALUES(?,?,?,?,?,?,'1') ON DUPLICATE KEY UPDATE count = count + 1";

			LocalDate date = LocalDate.now();
			try (Connection c = mitreIdStats.getConnection()) {
				try (PreparedStatement preparedStatement = c.prepareStatement(detailedStats)) {
					preparedStatement.setInt(1, date.getYear());
					preparedStatement.setInt(2, date.getMonthValue());
					preparedStatement.setInt(3, date.getDayOfMonth());
					preparedStatement.setString(4, idpEntityId);
					preparedStatement.setString(5, spIdentifier);
					preparedStatement.setString(6, userId);
					preparedStatement.execute();
				}
				log.debug("The login log was successfully stored into database: ({},{},{},{},{})", idpEntityId, idpName, spIdentifier, spName, userId);
			} catch (SQLException ex) {
				log.warn("Detailed statistics weren't updated due to SQLException.");
				log.error("Caught SQLException", ex);
			}
		}
	}

	private String changeEncodingOfParam(String original, Charset source, Charset destination) {
		if (original != null && !original.isEmpty()) {
			byte[] sourceBytes = original.getBytes(source);
			return new String(sourceBytes, destination);
		}

		return null;
	}

	private void logUserLogin(HttpServletRequest req) {
		String clientId = req.getParameter(CLIENT_ID);

		if (clientId == null || clientId.isEmpty()) {
			return;
		}

		ClientDetailsEntity client = clientService.loadClientByClientId(clientId);
		if (client == null) {
			return;
		}

		PerunPrincipal perunPrincipal = FiltersUtils.extractPerunPrincipal(req, config.getProxyExtSourceName());
		if (perunPrincipal == null) {
			return;
		}

		log.info("User identity: {}, service: {}, serviceName: {}, via IdP: {}", perunPrincipal.getExtLogin(),
				client.getClientId(), client.getClientName(), perunPrincipal.getExtSourceName() );
	}
}
