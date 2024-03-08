package it.eng.idsa.dataapp.web.rest.entity;

import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.entity.ContentType;
import org.apache.http.protocol.HTTP;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.gson.Gson;

import it.eng.idsa.dataapp.configuration.ECCProperties;
import it.eng.idsa.dataapp.model.OrionRequest;
import it.eng.idsa.dataapp.service.OrionContextBrokerService;
import it.eng.idsa.dataapp.util.ConstantUtil;
import it.eng.idsa.dataapp.web.rest.ProxyController;

@RestController

public class GetEntityController {

	private static final Logger logger = LoggerFactory.getLogger(GetEntityController.class);

	@Autowired
	private ProxyController proxyController;

	@Autowired
	private OrionContextBrokerService orionContextBrokerService;

	@Value("${application.fiware.contextpath.orionprovider}")
	private String contextPathOrionProvider;

	private ECCProperties eccProperties;

	public GetEntityController(ECCProperties eccProperties) {
		this.eccProperties = eccProperties;
	}

	@GetMapping(value = "/getentity/{entityId}", produces = "application/json")
	public ResponseEntity<?> GetEntity(@RequestHeader HttpHeaders httpHeaders,
			@PathVariable String entityId, HttpServletRequest request)
			throws URISyntaxException, JsonProcessingException, Exception {

		logger.info("ENG: request.getRequestURI()  {}", request.getRequestURI());
		logger.info("ENG: contextPathOrionProvider {}", contextPathOrionProvider);

		logger.info("httpHeaders             {}", httpHeaders);

		var reference = orionContextBrokerService.GetReference(entityId);

		if (reference == null) {
			var msg = MessageFormat.format("Reference not found per entity {0}", entityId);
			logger.info(msg);
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(msg);
		}

		var payload = new Gson().toJson(new OrionRequest(entityId, ConstantUtil.ORION_ORIGIN));
		// proxy request
		var bodyProxy = Map.ofEntries(
				Map.entry(ConstantUtil.MULTIPART_KEY, ConstantUtil.MULTIPART_FORM),
				Map.entry(ConstantUtil.FORWARD_TO, reference.get(ConstantUtil.FORWARD_TO)),
				Map.entry(ConstantUtil.MESSAGE_TYPE, ConstantUtil.ARTIFACT_REQUEST_MESSAGE),
				Map.entry(ConstantUtil.REQUESTED_ARTIFACT, reference.get(ConstantUtil.REQUESTED_ARTIFACT)),
				Map.entry(ConstantUtil.TRANSFER_CONTRACT, reference.get(ConstantUtil.TRANSFER_CONTRACT)),
				Map.entry(ConstantUtil.PAYLOAD, payload));

		logger.info("ENG: payload = " + payload);

		var headersProxy = new HttpHeaders();
		headersProxy.add("Authorization", eccProperties.getHeaderAuthorization());
		headersProxy.add(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());

		var response = proxyController.proxyRequest(headersProxy,
				JSONValue.toJSONString(bodyProxy),
				HttpMethod.POST);

		return response;
	}

}
