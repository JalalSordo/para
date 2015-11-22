/*
 * Copyright 2013-2015 Erudika. http://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.security;

import com.erudika.para.Para;
import com.erudika.para.core.App;
import com.erudika.para.core.CoreUtils;
import com.erudika.para.core.User;
import com.erudika.para.rest.RestUtils;
import com.erudika.para.utils.Config;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.Assert;
import org.springframework.web.filter.GenericFilterBean;

/**
 * Security filter that intercepts authentication requests (usually coming from the client-side)
 * and validates JWT tokens.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class JWTRestfulAuthFilter extends GenericFilterBean {

	private AuthenticationManager authenticationManager;
	private AntPathRequestMatcher authenticationRequestMatcher;
	private final FacebookAuthFilter facebookAuth;
	private final GoogleAuthFilter googleAuth;
	private final GitHubAuthFilter githubAuth;
	private final LinkedInAuthFilter linkedinAuth;
	private final TwitterAuthFilter twitterAuth;

	/**
	 * The default filter mapping
	 */
	public static final String JWT_ACTION = "jwt_auth";

	public JWTRestfulAuthFilter(String defaultFilterProcessesUrl) {
		this.twitterAuth = new TwitterAuthFilter("/");
		this.linkedinAuth = new LinkedInAuthFilter("/");
		this.googleAuth = new GoogleAuthFilter("/");
		this.facebookAuth = new FacebookAuthFilter("/");
		this.githubAuth = new GitHubAuthFilter("/");
		Assert.hasLength(defaultFilterProcessesUrl);
		setFilterProcessesUrl(defaultFilterProcessesUrl);
	}

	private void setFilterProcessesUrl(String filterProcessesUrl) {
		this.authenticationRequestMatcher = new AntPathRequestMatcher(filterProcessesUrl);
	}

	protected AuthenticationManager getAuthenticationManager() {
		return authenticationManager;
	}

	public void setAuthenticationManager(AuthenticationManager authenticationManager) {
		this.authenticationManager = authenticationManager;
	}

	@Override
    public void afterPropertiesSet() {
		Assert.notNull(authenticationManager, "authenticationManager cannot be null");
    }

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest request = (HttpServletRequest) req;
		HttpServletResponse response = (HttpServletResponse) res;
		if (authenticationRequestMatcher.matches(request)) {
			if (HttpMethod.POST.equals(request.getMethod())) {
				newTokenHandler(request, response);
			} else if (HttpMethod.GET.equals(request.getMethod())) {
				refreshTokenHandler(request, response);
			} else if (HttpMethod.DELETE.equals(request.getMethod())) {
				revokeAllTokensHandler(request, response);
			}
			return;
		} else if (RestRequestMatcher.INSTANCE.matches(request) &&
				SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				// validate token if present
				JWTAuthentication jwt = getJWTfromRequest(request);
				if (jwt != null) {
					User user = Para.getDAO().read(new App(jwt.getAppid()).getAppIdentifier(), jwt.getUserid());
					if (user != null) {
						Authentication auth = authenticationManager.authenticate(jwt);
						// success!
						SecurityContextHolder.getContext().setAuthentication(auth);
					}
				} else {
					response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
				}
			} catch (AuthenticationException authenticationException) {
				response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
				logger.debug("AuthenticationManager rejected JWT Authentication.", authenticationException);
			}
		}

		chain.doFilter(request, response);
	}

	@SuppressWarnings("unchecked")
	private boolean newTokenHandler(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		Response res = RestUtils.getEntity(request.getInputStream(), Map.class);
		if (res.getStatusInfo() != Response.Status.OK) {
			RestUtils.returnStatusResponse(response, res.getStatus(), res.getEntity().toString());
			return false;
		}
		Map<String, Object> entity = (Map<String, Object>) res.getEntity();
		String provider = (String) entity.get("provider");
		String appid = (String) entity.get("appid");
		String token = (String) entity.get("token");

		if (provider != null && appid != null && token != null) {
			App app = new App(appid);
			UserAuthentication userAuth = getOrCreateUser(app.getAppIdentifier(), provider, token);
			User user = SecurityUtils.getAuthenticatedUser(userAuth);
			if (user != null) {
				app = Para.getDAO().read(app.getId());
				if (app != null) {
					// issue token
					Date now = new Date();
					JWTClaimsSet.Builder claimsSet = new JWTClaimsSet.Builder();
					claimsSet.subject(user.getId());
					claimsSet.issueTime(now);
					claimsSet.expirationTime(new Date(now.getTime() + (Config.SESSION_TIMEOUT_SEC * 1000)));
					claimsSet.notBeforeTime(now);
					claimsSet.claim("appid", app.getId());

					SignedJWT newJWT = generateJWT(claimsSet.build(), app.getSecret());
					if (newJWT != null) {
						// clear revocation flag
						if (user.getRevokeTokensAt() != null) {
							user.setRevokeTokensAt(null);
							CoreUtils.overwrite(app.getAppIdentifier(), user);
						}
						succesHandler(response, user, newJWT);
						return true;
					}
				} else {
					RestUtils.returnStatusResponse(response, HttpServletResponse.SC_BAD_REQUEST,
							"User belongs to an app that does not exist.");
					return false;
				}
			} else {
				RestUtils.returnStatusResponse(response, HttpServletResponse.SC_BAD_REQUEST,
						"Failed to authenticate user with " + provider);
				return false;
			}
		}
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_BAD_REQUEST,
				"Some of the required query parameters 'provider', 'appid', 'token', are missing.");
		return false;
	}

	private boolean refreshTokenHandler(HttpServletRequest request, HttpServletResponse response) {
		// check or reissue token
		JWTAuthentication jwt = getJWTfromRequest(request);
		if (jwt != null) {
			try {
				App app = Para.getDAO().read(jwt.getAppid());
				User user = SecurityUtils.getAuthenticatedUser(jwt);
				if (user != null && app != null) {
					Date now = new Date();
					boolean validJwt = SecurityUtils.isValidJWToken(app, jwt.getJwt());
					boolean validSig = SecurityUtils.isValidButExpiredJWToken(app, jwt.getJwt());
					boolean userMustLogin = user.getRevokeTokensAt() != null
							&& new Date(user.getRevokeTokensAt()).before(now);
					if (!userMustLogin) {
						if (validJwt) {
							succesHandler(response, user, jwt.getJwt());
							return true;
						} else if (validSig) {
							SignedJWT newToken = generateJWT(jwt.getClaims(), app.getSecret());
							if (newToken != null) {
								succesHandler(response, user, newToken);
								return true;
							}
						}
					}
				}
			} catch (Exception ex) {
				logger.warn(ex);
			}
		}
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "User must reauthenticate.");
		return false;
	}

	private boolean revokeAllTokensHandler(HttpServletRequest request, HttpServletResponse response) {
		String at = request.getParameter("revokeTokensAt");
		Long atStamp = NumberUtils.toLong(at, System.currentTimeMillis());
		JWTAuthentication jwt = getJWTfromRequest(request);
		if (jwt != null) {
			User user = SecurityUtils.getAuthenticatedUser(jwt);
			if (user != null) {
				user.setRevokeTokensAt(atStamp);
				CoreUtils.overwrite(new App(jwt.getAppid()).getAppIdentifier(), user);
				RestUtils.returnStatusResponse(response, HttpServletResponse.SC_OK,
						"All tokens will be revoked at " + new Date(atStamp));
			}
		}
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		RestUtils.returnStatusResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
				"Invalid or expired token.");
		return false;
	}

	private void succesHandler(HttpServletResponse response, User user, final SignedJWT token) {
		if (user != null && token != null) {
			Map<String, Object> result = new HashMap<String, Object>();
			result.put("user", user);
			result.put("jwt", new HashMap<String, Object>() {{
					try {
						put("access_token", token.serialize());
						put("expires", token.getJWTClaimsSet().getExpirationTime().getTime());
					} catch (ParseException ex) {
						logger.info("Unable to parse JWT.", ex);
					}
			}});
			RestUtils.returnObjectResponse(response, result);
		} else {
			RestUtils.returnStatusResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Null token.");
		}
	}

	protected SignedJWT generateJWT(JWTClaimsSet claimsSet, String secret) {
		if (claimsSet != null && secret != null) {
			try {
				JWSSigner signer = new MACSigner(secret);
				SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
				signedJWT.sign(signer);
				return signedJWT;
			} catch(JOSEException e) {
				logger.warn("Unable to sign JWT.", e);
			}
		}
		return null;
    }

	private JWTAuthentication getJWTfromRequest(HttpServletRequest request) {
		String token = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (token == null) {
			token = request.getParameter(HttpHeaders.AUTHORIZATION);
		}
		if (!StringUtils.isBlank(token) && token.contains("Bearer")) {
			try {
				SignedJWT jwt = SignedJWT.parse(token.substring(6).trim());
				String userid = jwt.getJWTClaimsSet().getSubject();
				String appid = (String) jwt.getJWTClaimsSet().getClaim("appid");
				User user = Para.getDAO().read(new App(appid).getAppIdentifier(), userid);
				if (user != null) {
					return new JWTAuthentication(new AuthenticatedUserDetails(user)).withJWT(jwt);
				}
			} catch (ParseException e) {
				logger.debug("Unable to parse JWT.", e);
			}
		}
		return null;
	}

	private UserAuthentication getOrCreateUser(String appid, String identityProvider, String accessToken)
			throws IOException, AuthenticationException {
		if ("facebook".equals(identityProvider)) {
			return facebookAuth.getOrCreateUser(appid, accessToken);
		} else if ("google".equals(identityProvider)) {
			return googleAuth.getOrCreateUser(appid, accessToken);
		} else if ("github".equals(identityProvider)) {
			return githubAuth.getOrCreateUser(appid, accessToken);
		} else if ("linkedin".equals(identityProvider)) {
			return linkedinAuth.getOrCreateUser(appid, accessToken);
		} else if ("twitter".equals(identityProvider)) {
			String[] tokens = accessToken.split(Config.SEPARATOR);
			return twitterAuth.getOrCreateUser(appid, tokens[0], tokens[1]);
		}
		return null;
	}
}