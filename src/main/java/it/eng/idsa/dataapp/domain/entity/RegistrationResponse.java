package it.eng.idsa.dataapp.domain.entity;

public class RegistrationResponse {
    public RegistrationResponse(String pidValue) {
        super();
        this.pid = pidValue;
    }

    public RegistrationResponse() {
        super();
    }

    public RegistrationResponse(String pid, String smartContractAddress) {
        this.pid = pid;
        this.smartContractAddress = smartContractAddress;
    }

    private String pid;
    private String smartContractAddress;

    public String getPid() {
        return pid;
    }

    public void setPid(String property) {
        this.pid = property;
    }

    public String getSmartContractAddress() {
        return smartContractAddress;
    }

    public void setSmartContractAddress(String smartContractAddress) {
        this.smartContractAddress = smartContractAddress;
    }
}
