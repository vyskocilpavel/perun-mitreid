package cz.muni.ics.oidc.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import cz.muni.ics.oidc.models.RichUser;
import cz.muni.ics.oidc.server.claims.ClaimModifier;
import cz.muni.ics.oidc.server.claims.ClaimSource;
import cz.muni.ics.oidc.server.claims.ClaimSourceInitContext;
import cz.muni.ics.oidc.server.claims.ClaimSourceProduceContext;
import cz.muni.ics.oidc.server.claims.PerunCustomClaimDefinition;
import cz.muni.ics.oidc.server.claims.ClaimModifierInitContext;
import cz.muni.ics.oidc.server.claims.sources.PerunAttributeClaimSource;
import cz.muni.ics.oidc.server.connectors.PerunConnector;
import org.mitre.openid.connect.model.Address;
import org.mitre.openid.connect.model.DefaultAddress;
import org.mitre.openid.connect.model.UserInfo;
import org.mitre.openid.connect.repository.UserInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Provides data about a user.
 *
 * @author Martin Kuba makub@ics.muni.cz
 * @author Peter Jancus jancus@ics.muni.cz
 */
public class PerunUserInfoRepository implements UserInfoRepository {

	private final static Logger log = LoggerFactory.getLogger(PerunUserInfoRepository.class);

	private static final String SOURCE_CLASS = ".sourceClass";
	private static final String MODIFIER_CLASS = ".modifierClass";

	private PerunConnector perunConnector;
	private Properties properties;

	private String subAttribute;
	private ClaimModifier subModifier;
	private String preferredUsernameAttribute;
	private String givenNameAttribute;
	private String familyNameAttribute;
	private String middleNameAttribute;
	private String fullNameAttribute;
	private String emailAttribute;
	private String addressAttribute;
	private String phoneAttribute;
	private String zoneinfoAttribute;
	private String localeAttribute;
	private List<String> customClaimNames;
	private List<PerunCustomClaimDefinition> customClaims = new ArrayList<>();

	public void setProperties(Properties properties) {
		this.properties = properties;
	}

	public void setPerunConnector(PerunConnector perunConnector) {
		this.perunConnector = perunConnector;
	}

	public void setSubAttribute(String subAttribute) {
		this.subAttribute = subAttribute;
	}

	public void setPreferredUsernameAttribute(String preferredUsernameAttribute) {
		this.preferredUsernameAttribute = preferredUsernameAttribute;
	}

	public void setGivenNameAttribute(String givenNameAttribute) {
		this.givenNameAttribute = givenNameAttribute;
	}

	public void setFamilyNameAttribute(String familyNameAttribute) {
		this.familyNameAttribute = familyNameAttribute;
	}

	public void setMiddleNameAttribute(String middleNameAttribute) {
		this.middleNameAttribute = middleNameAttribute;
	}

	public void setFullNameAttribute(String fullNameAttribute) {
		this.fullNameAttribute = fullNameAttribute;
	}

	public void setEmailAttribute(String emailAttribute) {
		this.emailAttribute = emailAttribute;
	}

	public void setAddressAttribute(String addressAttribute) {
		this.addressAttribute = addressAttribute;
	}

	public void setPhoneAttribute(String phoneAttribute) {
		this.phoneAttribute = phoneAttribute;
	}

	public void setZoneinfoAttribute(String zoneinfoAttribute) {
		this.zoneinfoAttribute = zoneinfoAttribute;
	}

	public void setLocaleAttribute(String localeAttribute) {
		this.localeAttribute = localeAttribute;
	}

	public void setCustomClaimNames(List<String> customClaimNames) {
		this.customClaimNames = customClaimNames;
	}

	@PostConstruct
	public void postInit() {
		log.debug("trying to load modifier for attribute.openid.sub");
		subModifier = loadClaimValueModifier("attribute.openid.sub");
		//custom claims
		this.customClaims = new ArrayList<>(customClaimNames.size());
		for (String claim : customClaimNames) {
			String propertyPrefix = "custom.claim." + claim;
			//get scope
			String scopeProperty = propertyPrefix + ".scope";
			String scope = properties.getProperty(scopeProperty);
			if (scope == null) {
				log.error("property {} not found, skipping custom claim {}", scopeProperty, claim);
				continue;
			}
			//get ClaimSource
			ClaimSource claimSource = loadClaimSource(propertyPrefix);
			//optional claim value modifier
			ClaimModifier claimModifier = loadClaimValueModifier(propertyPrefix);
			//add claim definition
			customClaims.add(new PerunCustomClaimDefinition(scope, claim, claimSource, claimModifier));
		}
	}

	private ClaimModifier loadClaimValueModifier(String propertyPrefix) {
		String modifierClass = properties.getProperty(propertyPrefix + MODIFIER_CLASS);
		if (modifierClass != null) {
			try {
				Class<?> rawClazz = Class.forName(modifierClass);
				if (!ClaimModifier.class.isAssignableFrom(rawClazz)) {
					log.error("modifier class {} does not extend ClaimModifier", modifierClass);
					return null;
				}
				@SuppressWarnings("unchecked") Class<ClaimModifier> clazz = (Class<ClaimModifier>) rawClazz;
				Constructor<ClaimModifier> constructor = clazz.getConstructor(ClaimModifierInitContext.class);
				ClaimModifierInitContext ctx = new ClaimModifierInitContext(propertyPrefix, properties);
				ClaimModifier claimModifier = constructor.newInstance(ctx);
				log.info("loaded claim modifier '{}' for {}", claimModifier, propertyPrefix);
				return claimModifier;
			} catch (ClassNotFoundException e) {
				log.error("modifier class {} not found", modifierClass);
				return null;
			} catch (NoSuchMethodException e) {
				log.error("modifier class {} does not have proper constructor", modifierClass);
				return null;
			} catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
				log.error("cannot instantiate " + modifierClass, e);
				log.error("modifier class {} cannot be instantiated", modifierClass);
				return null;
			}
		} else {
			log.debug("property {} not found, skipping", propertyPrefix + MODIFIER_CLASS);
			return null;
		}
	}

	private ClaimSource loadClaimSource(String propertyPrefix) {
		String sourceClass = properties.getProperty(propertyPrefix + SOURCE_CLASS, PerunAttributeClaimSource.class.getName());
		try {
			Class<?> rawClazz = Class.forName(sourceClass);
			if (!ClaimSource.class.isAssignableFrom(rawClazz)) {
				log.error("source class {} does not extend ClaimSource", sourceClass);
				return null;
			}
			@SuppressWarnings("unchecked") Class<ClaimSource> clazz = (Class<ClaimSource>) rawClazz;
			Constructor<ClaimSource> constructor = clazz.getConstructor(ClaimSourceInitContext.class);
			ClaimSourceInitContext ctx = new ClaimSourceInitContext(propertyPrefix, properties);
			ClaimSource claimSource = constructor.newInstance(ctx);
			log.info("loaded claim source '{}' for {}", claimSource, propertyPrefix);
			return claimSource;
		} catch (ClassNotFoundException e) {
			log.error("source class {} not found", sourceClass);
			return null;
		} catch (NoSuchMethodException e) {
			log.error("source class {} does not have proper constructor", sourceClass);
			return null;
		} catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
			log.error("cannot instantiate " + sourceClass, e);
			log.error("source class {} cannot be instantiated", sourceClass);
			return null;
		}
	}

	List<PerunCustomClaimDefinition> getCustomClaims() {
		return customClaims;
	}

	@Override
	public UserInfo getByUsername(String username) {
		log.trace("getByUsername({})", username);
		try {
			return cache.get(username);
		} catch (UncheckedExecutionException | ExecutionException e) {
			log.error("cannot get user from cache", e);
			return null;
		}
	}

	@Override
	public UserInfo getByEmailAddress(String email) {
		log.trace("getByEmailAddress({})", email);
		throw new RuntimeException("PerunUserInfoRepository.getByEmailAddress() not implemented");
	}

	public PerunUserInfoRepository() {
		this.cache = CacheBuilder.newBuilder()
				.maximumSize(100)
				.expireAfterAccess(60, TimeUnit.SECONDS)
				.build(cacheLoader);
	}

	private LoadingCache<String, UserInfo> cache;

	@SuppressWarnings("FieldCanBeLocal")
	private CacheLoader<String, UserInfo> cacheLoader = new CacheLoader<String, UserInfo>() {
		@Override
		public UserInfo load(String username) {
			log.trace("load({})", username);
			PerunUserInfo ui = new PerunUserInfo();
			long perunUserId = Long.parseLong(username);
			RichUser richUser = perunConnector.getUserAttributes(perunUserId);
			//process


			String sub = richUser.getAttributeValue(subAttribute);
			if (sub == null) {
				throw new RuntimeException("cannot get sub from attribute " + subAttribute + " for username " + username);
			}
			if (subModifier != null) {
				//transform sub value
				sub = subModifier.modify(sub);
			}

			ui.setId(perunUserId);
			ui.setSub(sub); // Subject - Identifier for the End-User at the Issuer.

			ui.setPreferredUsername(richUser.getAttributeValue(preferredUsernameAttribute)); // Shorthand name by which the End-User wishes to be referred to at the RP
			ui.setGivenName(richUser.getAttributeValue(givenNameAttribute)); //  Given name(s) or first name(s) of the End-User
			ui.setFamilyName(richUser.getAttributeValue(familyNameAttribute)); // Surname(s) or last name(s) of the End-User
			ui.setMiddleName(richUser.getAttributeValue(middleNameAttribute)); //  Middle name(s) of the End-User
			ui.setName(richUser.getAttributeValue(fullNameAttribute)); // End-User's full name
			//ui.setNickname(); // Casual name of the End-User
			//ui.setProfile(); //  URL of the End-User's profile page.
			//ui.setPicture(); // URL of the End-User's profile picture.
			//ui.setWebsite(); // URL of the End-User's Web page or blog.
			ui.setEmail(richUser.getAttributeValue(emailAttribute)); // End-User's preferred e-mail address.
			//ui.setEmailVerified(true); // True if the End-User's e-mail address has been verified
			//ui.setGender("male"); // End-User's gender. Values defined by this specification are female and male.
			//ui.setBirthdate("1975-01-01");//End-User's birthday, represented as an ISO 8601:2004 [ISO8601‑2004] YYYY-MM-DD format.
			ui.setZoneinfo(richUser.getAttributeValue(zoneinfoAttribute));//String from zoneinfo [zoneinfo] time zone database, For example, Europe/Paris
			ui.setLocale(richUser.getAttributeValue(localeAttribute)); //  For example, en-US or fr-CA.
			ui.setPhoneNumber(richUser.getAttributeValue(phoneAttribute)); //[E.164] is RECOMMENDED as the format, for example, +1 (425) 555-121
			//ui.setPhoneNumberVerified(true); // True if the End-User's phone number has been verified
			//ui.setUpdatedTime(Long.toString(System.currentTimeMillis()/1000L));// value is a JSON number representing the number of seconds from 1970-01-01T0:0:0Z as measured in UTC until the date/time
			Address address = new DefaultAddress();
			address.setFormatted(richUser.getAttributeValue(addressAttribute));
			//address.setStreetAddress("Šumavská 15");
			//address.setLocality("Brno");
			//address.setPostalCode("61200");
			//address.setCountry("Czech Republic");
			ui.setAddress(address);
			//custom claims
			ClaimSourceProduceContext pctx = new ClaimSourceProduceContext(perunUserId, sub, richUser, perunConnector);
			log.trace("processing custom claims");
			for (PerunCustomClaimDefinition pccd : customClaims) {
				log.trace("producing value for claim {}",pccd.getClaim());
				JsonNode claimInJson = pccd.getClaimSource().produceValue(pctx);
				log.trace("produced value {}",claimInJson);
				if(claimInJson==null) {
					log.warn("claim {} is null",pccd.getClaim());
					continue;
				}
				ClaimModifier claimModifier = pccd.getClaimModifier();
				if (claimModifier != null) {
					log.debug("modifying values of claim '{}' using {}", pccd.getClaim(), claimModifier);
					//transform values
					if (claimInJson.isTextual()) {
						//transform a simple string value
						claimInJson = TextNode.valueOf(claimModifier.modify(claimInJson.asText()));
					} else if (claimInJson.isArray()) {
					    claimInJson = claimInJson.deepCopy();
						//transform all strings in an array
						ArrayNode arrayNode = (ArrayNode) claimInJson;
						for (int i = 0; i < arrayNode.size(); i++) {
							JsonNode item = arrayNode.get(i);
							if (item.isTextual()) {
								String original = item.asText();
								String modified = claimModifier.modify(original);
								log.debug("transforming value '{}' to '{}'", original, modified);
								arrayNode.set(i, TextNode.valueOf(modified));
							}
						}
					}
				}
				ui.getCustomClaims().put(pccd.getClaim(), claimInJson);
			}
			log.trace("user loaded");
			return ui;
		}
	};

}
