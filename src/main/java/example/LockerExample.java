package example;

import com.google.gson.reflect.TypeToken;
import locker.LockerClient;
import locker.exception.LockerError;
import locker.model.Environment;
import locker.model.LockerCollection;
import locker.model.Secret;
import locker.param.secret.SecretCreateParams;
import locker.param.secret.SecretUpdateParams;



public class LockerExample {
    public static void main(String[] args) {
        LockerClient client = new LockerClient(
                "your_access_key_id",
                "your_access_key_secret"
        );
        SecretCreateParams secretCreateParams = SecretCreateParams.builder()
                .setValue("your_secret_value")
                .setKey("your_secret_key")
                .setDescription("your_secret_description")
                .build();
        SecretUpdateParams secretUpdateParams = SecretUpdateParams.builder()
                .setKey("your_update_secret_key")
                .setValue("your_update_secret_value")
                .setDescription("your_update_secret_description")
                .build();

        try {
            // List all secrets
            Class<?> type = new TypeToken<LockerCollection<Environment>>() {
            }.getRawType();
            LockerCollection<Environment> listSecrets = (LockerCollection<Environment>) client.environments().list(type);
            System.out.println(listSecrets);

            // Retrieve secret
            Secret secret = client.secrets().retrieve("your_secret_key", Secret.class);
            System.out.println(secret);

            // Create new secret
            Secret newSecret = client.secrets().create(secretCreateParams, Secret.class);
            System.out.println(newSecret);

            // Update new secret
            Secret updatedSecret = client.secrets().modify("your_secret_key", secretUpdateParams, Secret.class);
            System.out.println(updatedSecret);


        } catch (LockerError e) {
            e.printStackTrace();
        }

    }
}
