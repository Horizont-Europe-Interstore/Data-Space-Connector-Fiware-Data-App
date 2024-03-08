package it.eng.idsa.dataapp.web.rest.entity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import it.eng.idsa.dataapp.domain.ProxyRequest;
import it.eng.idsa.dataapp.service.ProxyService;

@RestController

public class CreateEntityController {

	private static final Logger logger = LoggerFactory.getLogger(CreateEntityController.class);

	@Autowired
	private ProxyService proxyService;

	@Value("${application.fiware.contextpath.orionprovider}")
	private String contextPathOrionProvider;

	@Value("${application.orion.protocol}")
	private String orionProtocol;

	@Value("${application.orion.host}")
	private String orionHost;

	@Value("${application.orion.port.createentity}")
	private String orionPort;

	@PostMapping(value = "/createentity", produces = "application/json")
	public ResponseEntity<?> CreateEntity(@RequestHeader HttpHeaders httpHeaders,
			@RequestBody(required = true) String body, HttpServletRequest request)
			throws URISyntaxException, Exception {
		logger.info("HTTP Headers {}",
				httpHeaders.entrySet().stream().map(Map.Entry::toString).collect(Collectors.joining(";", "[", "]")));

		logger.info("ENG: request.getRequestURI()       {}", request.getRequestURI());
		logger.info("ENG: request httpHeaders {}", httpHeaders);
		logger.info("ENG: request     {}", request);
		logger.info("ENG: body        {}", body);

		var orionHeaders = new HttpHeaders();
		orionHeaders.add("Content-Length", String.valueOf(body.length()));
		orionHeaders.add("Content-Type", "application/ld+json");
		ProxyRequest proxyRequest = new ProxyRequest();
		logger.info("ENG: proxyRequest.getPayload() {}", proxyRequest.getPayload());

		var uri = new URI(orionProtocol, null, orionHost, Integer.valueOf(orionPort), "/" +
				contextPathOrionProvider + "/",
				null, null);

		return proxyService.proxyCreationEntityForm(orionHeaders, body, uri);

	}
}
