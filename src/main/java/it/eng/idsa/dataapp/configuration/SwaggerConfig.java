package it.eng.idsa.dataapp.configuration;

import javax.servlet.ServletContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.paths.RelativePathProvider;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@Configuration
@EnableSwagger2
public class SwaggerConfig {

	@Value("${swagger.base.path}")
	private String basePath;

	@Bean
	public Docket api(ServletContext servletContext) {
		return new Docket(DocumentationType.SWAGGER_2)
				.select()
				.apis(RequestHandlerSelectors.basePackage("it.eng.idsa.dataapp.web.rest.entity"))
				.paths(PathSelectors.any())
				.build()
				.pathProvider(new RelativePathProvider(servletContext) {
					@Override
					public String getApplicationBasePath() {
						return basePath + super.getApplicationBasePath();
					}
				})
				.useDefaultResponseMessages(false);
	}

	@Bean
	public RestTemplate getRestTemplate() {
		// return new RestTemplate();
		// fix for Invalid HTTP method: PATCH
		HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
		requestFactory.setReadTimeout(600000);
		requestFactory.setConnectTimeout(600000);
		return new RestTemplate(requestFactory);
	}

}
