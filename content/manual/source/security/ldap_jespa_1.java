package com.company.sample.web;

import com.haulmont.cuba.core.global.AppBeans;
import com.haulmont.cuba.core.global.Configuration;
import com.haulmont.cuba.core.global.GlobalConfig;
import com.haulmont.cuba.core.global.Messages;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.security.global.LoginException;
import com.haulmont.cuba.web.auth.CubaAuthProvider;
import com.haulmont.cuba.web.auth.DomainAliasesResolver;
import jespa.http.HttpSecurityService;
import jespa.ntlm.NtlmSecurityProvider;
import jespa.security.PasswordCredential;
import jespa.security.SecurityProviderException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.inject.Inject;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class JespaAuthProvider extends HttpSecurityService implements CubaAuthProvider {

    private static class DomainInfo {
        private String bindStr;
        private String acctName;
        private String acctPassword;

        private DomainInfo(String bindStr, String acctName, String acctPassword) {
            this.acctName = acctName;
            this.acctPassword = acctPassword;
            this.bindStr = bindStr;
        }
    }

    private static Map<String, DomainInfo> domains = new HashMap<>();

    private static String defaultDomain;

    private Log log = LogFactory.getLog(getClass());

    @Inject
    private Configuration configuration;

    @Inject
    private Messages messages;

    @SuppressWarnings("deprecation")
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

        initDomains();

        Map<String, String> properties = new HashMap<>();

        properties.put("jespa.bindstr", getBindStr());
        properties.put("jespa.service.acctname", getAcctName());
        properties.put("jespa.service.password", getAcctPassword());
        properties.put("jespa.account.canonicalForm", "3");
        properties.put("jespa.log.path", configuration.getConfig(GlobalConfig.class).getLogDir() + "/jespa.log");
        properties.put("http.parameter.anonymous.name", "anon");

        fillFromSystemProperties(properties);

        try {
            super.init(properties);
        } catch (SecurityProviderException e) {
            throw new ServletException(e);
        }
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        if (httpServletRequest.getHeader("User-Agent") != null) {
            String ua = httpServletRequest.getHeader("User-Agent").toLowerCase();
            boolean windows = ua.contains("windows");
            boolean gecko = ua.contains("gecko") && !ua.contains("webkit");
            if (!windows && gecko) {
                chain.doFilter(request, response);
                return;
            }
        }
        super.doFilter(request, response, chain);
    }

    @Override
    public void authenticate(String login, String password, Locale loc) throws LoginException {
        DomainAliasesResolver aliasesResolver = AppBeans.get(DomainAliasesResolver.NAME);

        String domain;
        String userName;

        int atSignPos = login.indexOf("@");
        if (atSignPos >= 0) {
            String domainAlias = login.substring(atSignPos + 1);
            domain = aliasesResolver.getDomainName(domainAlias).toUpperCase();
        } else {
            int slashPos = login.indexOf('\\');
            if (slashPos <= 0) {
                throw new LoginException("Invalid name: %s", login);
            }
            String domainAlias = login.substring(0, slashPos);
            domain = aliasesResolver.getDomainName(domainAlias).toUpperCase();
        }

        userName = login;

        DomainInfo domainInfo = domains.get(domain);
        if (domainInfo == null) {
            throw new LoginException("Unknown domain: %s", domain);
        }

        Map<String, String> params = new HashMap<>();
        params.put("bindstr", domainInfo.bindStr);
        params.put("service.acctname", domainInfo.acctName);
        params.put("service.password", domainInfo.acctPassword);
        params.put("account.canonicalForm", "3");
        fillFromSystemProperties(params);

        NtlmSecurityProvider provider = new NtlmSecurityProvider(params);
        try {
            PasswordCredential credential = new PasswordCredential(userName, password.toCharArray());
            provider.authenticate(credential);
        } catch (SecurityProviderException e) {
            throw new LoginException("Authentication error: %s", e.getMessage());
        }
    }

    private void initDomains() {
        String domainsStr = AppContext.getProperty("cuba.web.activeDirectoryDomains");
        if (!StringUtils.isBlank(domainsStr)) {
            String[] strings = domainsStr.split(";");
            for (int i = 0; i < strings.length; i++) {
                String domain = strings[i];
                domain = domain.trim();
                if (!StringUtils.isBlank(domain)) {
                    String[] parts = domain.split("\\|");
                    if (parts.length != 4) {
                        log.error("Invalid ActiveDirectory domain definition: " + domain);
                        break;
                    } else {
                        domains.put(parts[0], new DomainInfo(parts[1], parts[2], parts[3]));
                        if (i == 0)
                            defaultDomain = parts[0];
                    }
                }
            }
        }
    }

    public String getDefaultDomain() {
        return defaultDomain != null ? defaultDomain : "";
    }

    public String getBindStr() {
        return getBindStr(getDefaultDomain());
    }

    public String getBindStr(String domain) {
        initDomains();
        DomainInfo domainInfo = domains.get(domain);
        return domainInfo != null ? domainInfo.bindStr : "";
    }

    public String getAcctName() {
        return getAcctName(getDefaultDomain());
    }

    public String getAcctName(String domain) {
        initDomains();
        DomainInfo domainInfo = domains.get(domain);
        return domainInfo != null ? domainInfo.acctName : "";
    }

    public String getAcctPassword() {
        return getAcctPassword(getDefaultDomain());
    }

    public String getAcctPassword(String domain) {
        initDomains();
        DomainInfo domainInfo = domains.get(domain);
        return domainInfo != null ? domainInfo.acctPassword : "";
    }

    public void fillFromSystemProperties(Map<String, String> params) {
        for (String name : AppContext.getPropertyNames()) {
            if (name.startsWith("jespa.")) {
                params.put(name, AppContext.getProperty(name));
            }
        }
    }
}