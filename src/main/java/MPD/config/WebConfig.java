package MPD.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.resource.ResourceResolver;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Add Gson at position 0 so JsonElement / JsonObject / JsonArray are serialised
     * correctly, while keeping all Spring-default converters (ByteArray, String,
     * Resource, …) that other controllers depend on.
     * Note: extendMessageConverters runs AFTER the defaults are populated, so
     * inserting at index 0 just makes Gson win for content negotiation.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        Gson gson = new GsonBuilder().serializeNulls().create();
        GsonHttpMessageConverter gsonConverter = new GsonHttpMessageConverter();
        gsonConverter.setGson(gson);
        converters.add(0, gsonConverter);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry
            .addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(new SpaFallbackResolver());
    }

    static class SpaFallbackResolver implements ResourceResolver {

        private static final ClassPathResource INDEX =
            new ClassPathResource("static/index.html");

        @Override
        public Resource resolveResource(HttpServletRequest req, String path,
                                        List<? extends Resource> locations,
                                        ResourceResolverChain chain) {
            Resource r = chain.resolveResource(req, path, locations);
            if (r == null && req != null && !req.getRequestURI().contains("Servlet"))
                return INDEX.exists() ? INDEX : null;
            return r;
        }

        @Override
        public String resolveUrlPath(String resourcePath,
                                     List<? extends Resource> locations,
                                     ResourceResolverChain chain) {
            return chain.resolveUrlPath(resourcePath, locations);
        }
    }
}
