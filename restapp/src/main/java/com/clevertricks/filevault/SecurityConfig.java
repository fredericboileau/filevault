package com.clevertricks.filevault;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${KEYCLOAK_END_SESSION_URI:http://localhost:8180/realms/filevault/protocol/openid-connect/logout}")
    private String endSessionUri;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .defaultSuccessUrl("/", true)
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService())))
                .logout(logout -> logout
                        .logoutSuccessHandler(keycloakLogoutHandler())
                        .invalidateHttpSession(true)
                        .clearAuthentication(true));
        return http.build();
    }

    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return userRequest -> {
            OidcUser oidcUser = delegate.loadUser(userRequest);
            Set<GrantedAuthority> authorities = new HashSet<>(oidcUser.getAuthorities());

            // Realm roles
            Map<String, Object> realmAccess = oidcUser.getClaimAsMap("realm_access");
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) realmAccess.get("roles");
                if (roles != null) {
                    roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                            .forEach(authorities::add);
                }
            }

            // Client roles (scoped: ROLE_<CLIENT-ID>_<ROLE>)
            Map<String, Object> resourceAccess = oidcUser.getClaimAsMap("resource_access");
            if (resourceAccess != null) {
                resourceAccess.entrySet().forEach(entry -> {
                    String clientId = entry.getKey().toUpperCase();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> clientMap = (Map<String, Object>) entry.getValue();
                    @SuppressWarnings("unchecked")
                    List<String> roles = (List<String>) clientMap.get("roles");
                    if (roles != null) {
                        roles.stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + clientId + "_" + role.toUpperCase()))
                                .forEach(authorities::add);
                    }
                });
            }

            return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
        };
    }

    private LogoutSuccessHandler keycloakLogoutHandler() {
        return (request, response, authentication) -> {
            String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
            StringBuilder logoutUrl = new StringBuilder(endSessionUri);
            logoutUrl.append("?post_logout_redirect_uri=").append(baseUrl);
            if (authentication instanceof OAuth2AuthenticationToken token) {
                OidcUser user = (OidcUser) token.getPrincipal();
                logoutUrl.append("&id_token_hint=").append(user.getIdToken().getTokenValue());
            }
            response.sendRedirect(logoutUrl.toString());
        };
    }
}
