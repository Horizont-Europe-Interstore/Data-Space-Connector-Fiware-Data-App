package it.eng.idsa.dataapp.service;

import it.eng.idsa.dataapp.model.RequestContract;

public interface RequestTransferContractService {
    RequestContract RequestTransferContract(String forwardTo) throws Exception;
}
