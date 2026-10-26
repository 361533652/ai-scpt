package com.gmscpt.server.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;

/**
 * 静态资源与 SPA 回退。
 *
 * <p>前端是 Vite 构建出来的单页应用，产物直接打进同一个 fat jar 的
 * {@code classpath:/static/}。任何非 {@code /api} 的未命中路径都回退到
 * {@code index.html}（前端路由接管），否则刷新页面就是 404。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public FilterRegistrationBean<ApiSecurityFilter> apiSecurityFilter(AccessToken token, GmScriptProperties props) {
        FilterRegistrationBean<ApiSecurityFilter> bean =
                new FilterRegistrationBean<>(new ApiSecurityFilter(token, props));
        bean.addUrlPatterns("/api/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }

    @Bean
    public AccessToken accessToken() {
        return new AccessToken();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new org.springframework.web.servlet.resource.PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
