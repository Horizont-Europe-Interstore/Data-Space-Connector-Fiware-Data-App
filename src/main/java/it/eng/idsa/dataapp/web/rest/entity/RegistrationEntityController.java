package it.eng.idsa.dataapp.web.rest.entity;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.io.IOUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.HTTP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import it.eng.idsa.dataapp.domain.entity.RegistrationBody;
import it.eng.idsa.dataapp.domain.entity.RegistrationResponse;
import it.eng.idsa.dataapp.service.BlockchainService;
import it.eng.idsa.dataapp.service.OrionContextBrokerService;
import it.eng.idsa.dataapp.service.ProxyService;
import it.eng.idsa.dataapp.service.RequestTransferContractService;
import it.eng.idsa.dataapp.util.ConstantUtil;
import it.eng.idsa.dataapp.util.MessageUtil;

@RestController
public class RegistrationEntityController {

	private static final Logger logger = LoggerFactory.getLogger(RegistrationEntityController.class);

	@Autowired
	private RequestTransferContractService RequestTransferContractService;

	@Autowired
	private ProxyService proxyService;

	@Autowired
	private BlockchainService blockchainService;

	@Value("${application.fiware.contextpath.orionprovider}")
	private String contextPathOrionProvider;

	@Value("${application.fiware.contextpath.orionregistration}")
	private String contextPathOrionRegistration;

	@Value("${application.orion.protocol}")
	private String orionProtocol;

	@Value("${application.orion.host}")
	private String orionHost;

	@Value("${application.orion.port.registration}")
	private String orionPort;

	private Path dataLakeDirectory;

	private OrionContextBrokerService orionService;

	private MessageUtil messageUtil;

	public RegistrationEntityController(
			@Value("${application.dataLakeDirectory}") Path dataLakeDirectory,
			OrionContextBrokerService orionService, MessageUtil messageUtil) {
		this.dataLakeDirectory = dataLakeDirectory;
		this.orionService = orionService;
		this.messageUtil = messageUtil;
	}

	@CrossOrigin
	@PostMapping(value = "/registration", produces = "application/json")
	@ApiResponses(value = {
			@ApiResponse(code = 201, message = "Created"),
			@ApiResponse(code = 302, message = "Found")
	})
	@ResponseStatus(HttpStatus.CREATED)
	public ResponseEntity<RegistrationResponse> Registration(@RequestHeader HttpHeaders httpHeaders,
			@RequestBody(required = true) RegistrationBody body, HttpServletRequest request)
			throws URISyntaxException, Exception {

		logger.info("HTTP Headers {}",
				httpHeaders.entrySet().stream().map(Map.Entry::toString).collect(Collectors.joining(";", "[", "]")));

		logger.info("ENG: request.getRequestURI()       {}", request.getRequestURI());

		logger.info("ENG: httpHeaders                {}", httpHeaders);
		logger.info("ENG: request                    {}", request);
		logger.info("ENG: userData.get(\"entityId\") {}", body.getEntityId());
		logger.info("ENG: userData.get(\"eccUrl\")   {}", body.getEccUrl());

		String entityId = body.getEntityId();
		if (entityId.isEmpty()) {
			logger.error("entity IS EMPTY");
			return new ResponseEntity<RegistrationResponse>(new RegistrationResponse(), HttpStatus.BAD_REQUEST);
		}

		String forwardTo = body.getEccUrl();
		if (forwardTo.isEmpty()) {
			logger.error("eccUrl IS EMPTY");
			return new ResponseEntity<RegistrationResponse>(new RegistrationResponse(), HttpStatus.BAD_REQUEST);
		}

		// check reference doc does not exist!
		var reference = orionService.GetReference(entityId);
		if (reference != null) {
			String pid = messageUtil.getUUID(reference.get(ConstantUtil.TRANSFER_CONTRACT).toString());
			/* Blockchain Integration - read smart contract id */
			String smartContractAddress = reference.get(ConstantUtil.SMART_CONTRACT) != null ? reference.get(ConstantUtil.SMART_CONTRACT).toString() : null;
			
			if (pid != null) {
				logger.info("ENG: ENTITY REGISTRATION - ENTITY ID:{} - CONTRACT UUID: {} - SMART CONTRACT ID: {}", entityId, pid, smartContractAddress);
			}
			
			var headers = new HttpHeaders();
			headers.add(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
			return new ResponseEntity<RegistrationResponse>(new RegistrationResponse(pid, smartContractAddress),
					headers,
					HttpStatus.FOUND);
		}

		// generate new contract
		var requestedContract = RequestTransferContractService.RequestTransferContract(forwardTo);
		// var requestedContract = new RequestContract();
		logger.info("entity                  {}", entityId);
		logger.info("forward-to              {}", forwardTo);
		logger.info("httpHeaders             {}", httpHeaders);

		String payloadOrion = null;
		try {
			var bytes = Files.readAllBytes(dataLakeDirectory.resolve("orion-registration-entity.json"));
			var payloadOrionJSON = new Gson().fromJson(IOUtils.toString(bytes, "UTF8"), JsonElement.class)
					.getAsJsonObject();
			payloadOrionJSON.addProperty("endpoint", forwardTo);
			payloadOrionJSON.getAsJsonArray("information").get(0).getAsJsonObject()
					.getAsJsonArray("entities").get(0).getAsJsonObject()
					.addProperty("id", entityId);
			payloadOrion = payloadOrionJSON.toString();

		} catch (Exception e) {
			logger.error("Error while reading orion-registration-entity.json file from dataLakeDirectory {}", e);
			throw new Exception("Error while reading orion-registration-entity.json file from dataLakeDirectory");
		}

		logger.info("ENG: ************************************************************");
		logger.info("ENG: payloadOrion {}", payloadOrion);
		logger.info("ENG: ************************************************************");

		var uri = new URI(orionProtocol, null, orionHost, Integer.valueOf(orionPort),
				"/" + contextPathOrionRegistration + "/", null, null);

		
		/* Blockchain integration - generate smart contract */
		String smartContractAddress = blockchainService.createSmartContract(entityId);
		
		
		
		proxyService.proxyRegistrationEntityForm(payloadOrion, entityId, forwardTo,
		uri, orionService, requestedContract, smartContractAddress);

		String pid = messageUtil.getUUID(requestedContract.TransferContract);
		var headers = new HttpHeaders();
		headers.add(HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
		logger.info("ENG: ENTITY REGISTRATION - ENTITY ID:{} - CONTRACT UUID: {} - SMART CONTRACT ID: {}", entityId, pid, smartContractAddress);
		return new ResponseEntity<RegistrationResponse>(new RegistrationResponse(pid, smartContractAddress),
				headers,
				HttpStatus.CREATED);

	}
}
