package it.eng.idsa.dataapp.domain.entity;

public class RegistrationBody {
    private String EntityId;
    private String EccUrl;

    public String getEntityId() {
        return EntityId;
    }

    public void setEntityId(String property) {
        this.EntityId = property;
    }

    public String getEccUrl() {
        return EccUrl;
    }

    public void setEccUrl(String eccUrl) {
        this.EccUrl = eccUrl;
    }
}
