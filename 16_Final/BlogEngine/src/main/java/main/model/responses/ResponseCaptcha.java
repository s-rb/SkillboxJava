package main.model.responses;

public class ResponseCaptcha implements ResponseAPI {

    private String secret;
    private String image;

    public ResponseCaptcha(String secretCode, String imageBase64) {
        secret = secretCode;
        image = imageBase64;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }
}
