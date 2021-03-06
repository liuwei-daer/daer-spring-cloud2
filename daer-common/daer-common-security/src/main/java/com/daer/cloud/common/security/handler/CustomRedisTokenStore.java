package com.daer.cloud.common.security.handler;

import com.daer.cloud.common.security.config.Oauth2Properties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.common.ExpiringOAuth2RefreshToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2RefreshToken;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.AuthenticationKeyGenerator;
import org.springframework.security.oauth2.provider.token.DefaultAuthenticationKeyGenerator;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.store.redis.JdkSerializationStrategy;
import org.springframework.security.oauth2.provider.token.store.redis.RedisTokenStoreSerializationStrategy;

import java.util.*;

/**
 * @author liuwei
 * @date 2018-03-05
 */
public class CustomRedisTokenStore implements TokenStore {

    private static final String ACCESS = "access:";
    private static final String AUTH_TO_ACCESS = "auth_to_access:";
    private static final String AUTH = "auth:";
    private static final String REFRESH_AUTH = "refresh_auth:";
    private static final String ACCESS_TO_REFRESH = "access_to_refresh:";
    private static final String REFRESH = "refresh:";
    private static final String REFRESH_TO_ACCESS = "refresh_to_access:";
    private static final String CLIENT_ID_TO_ACCESS = "client_id_to_access:";
    private static final String UNAME_TO_ACCESS = "uname_to_access:";
    private final RedisConnectionFactory connectionFactory;
    private AuthenticationKeyGenerator authenticationKeyGenerator = new DefaultAuthenticationKeyGenerator();
    private RedisTokenStoreSerializationStrategy serializationStrategy = new JdkSerializationStrategy();
    private String prefix = "";
    private Oauth2Properties oauth2Properties;
    public CustomRedisTokenStore(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }
    public CustomRedisTokenStore(RedisConnectionFactory connectionFactory, Oauth2Properties oauth2Properties) {
        this.connectionFactory = connectionFactory;
        this.oauth2Properties = oauth2Properties;
    }

    public void setAuthenticationKeyGenerator(AuthenticationKeyGenerator authenticationKeyGenerator) {
        this.authenticationKeyGenerator = authenticationKeyGenerator;
    }

    public void setSerializationStrategy(RedisTokenStoreSerializationStrategy serializationStrategy) {
        this.serializationStrategy = serializationStrategy;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    private RedisConnection getConnection() {
        return this.connectionFactory.getConnection();
    }

    private byte[] serialize(Object object) {
        return this.serializationStrategy.serialize(object);
    }

    private byte[] serializeKey(String object) {
        return this.serialize(this.prefix + object);
    }

    private DefaultOAuth2AccessToken deserializeAccessToken(byte[] bytes) {
        return (DefaultOAuth2AccessToken)this.serializationStrategy.deserialize(bytes, DefaultOAuth2AccessToken.class);
    }

    private OAuth2Authentication deserializeAuthentication(byte[] bytes) {
        return (OAuth2Authentication)this.serializationStrategy.deserialize(bytes, OAuth2Authentication.class);
    }

    private OAuth2RefreshToken deserializeRefreshToken(byte[] bytes) {
        return (OAuth2RefreshToken)this.serializationStrategy.deserialize(bytes, OAuth2RefreshToken.class);
    }

    private byte[] serialize(String string) {
        return this.serializationStrategy.serialize(string);
    }

    private String deserializeString(byte[] bytes) {
        return this.serializationStrategy.deserializeString(bytes);
    }

    /**
     * 获取token
     * @param authentication
     * @return
     */
    @Override
    public OAuth2AccessToken getAccessToken(OAuth2Authentication authentication) {
        String key = this.authenticationKeyGenerator.extractKey(authentication);
        byte[] serializedKey = this.serializeKey(AUTH_TO_ACCESS + key);
        byte[] bytes = null;
        RedisConnection conn = this.getConnection();
        try {
            bytes = conn.get(serializedKey);
        } finally {
            conn.close();
        }

        OAuth2AccessToken accessToken = this.deserializeAccessToken(bytes);
        if(oauth2Properties.getMutiLogin() == 0){
            //移除token
            OAuth2AccessToken existingAccessToken = accessToken;
            OAuth2RefreshToken refreshToken = null;
            if (existingAccessToken != null) {
                if (existingAccessToken.getRefreshToken() != null) {
                    refreshToken = existingAccessToken.getRefreshToken();
                    removeRefreshToken(refreshToken);
                }
                removeAccessToken(existingAccessToken);
                accessToken = null; // 设置为空，这样每次登录会获取新的token
            }
        }
        return accessToken;

    }

    @Override
    public OAuth2Authentication readAuthentication(OAuth2AccessToken token) {
        return this.readAuthentication(token.getValue());
    }

    @Override
    public OAuth2Authentication readAuthentication(String token) {
        byte[] bytes;
        RedisConnection conn = this.getConnection();
        try {
            bytes = conn.get(this.serializeKey(AUTH + token));
        } finally {
            conn.close();
        }

        OAuth2Authentication var4 = this.deserializeAuthentication(bytes);
        return var4;
    }
    @Override
    public OAuth2Authentication readAuthenticationForRefreshToken(OAuth2RefreshToken token) {
        return this.readAuthenticationForRefreshToken(token.getValue());
    }

    public OAuth2Authentication readAuthenticationForRefreshToken(String token) {
        RedisConnection conn = this.getConnection();

        OAuth2Authentication var5;
        try {
            byte[] bytes = conn.get(this.serializeKey(REFRESH_AUTH + token));
            OAuth2Authentication auth = this.deserializeAuthentication(bytes);
            var5 = auth;
        } finally {
            conn.close();
        }


        return var5;
    }
    @Override
    public void storeAccessToken(OAuth2AccessToken token, OAuth2Authentication authentication) {
        byte[] serializedAccessToken = this.serialize((Object)token);
        byte[] serializedAuth = this.serialize((Object)authentication);
        byte[] accessKey = this.serializeKey(ACCESS + token.getValue());
        byte[] authKey = this.serializeKey(AUTH + token.getValue());
        byte[] authToAccessKey = this.serializeKey(AUTH_TO_ACCESS + this.authenticationKeyGenerator.extractKey(authentication));
        byte[] approvalKey = this.serializeKey(UNAME_TO_ACCESS + getApprovalKey(authentication));
        byte[] clientId = this.serializeKey(CLIENT_ID_TO_ACCESS + authentication.getOAuth2Request().getClientId());
        RedisConnection conn = this.getConnection();

        try {
            conn.openPipeline();
//            conn.set(accessKey, serializedAccessToken);
            conn.stringCommands().set(accessKey, serializedAccessToken);
            conn.set(authKey, serializedAuth);
            conn.set(authToAccessKey, serializedAccessToken);
            if (!authentication.isClientOnly()) {
                conn.rPush(approvalKey, new byte[][]{serializedAccessToken});
            }
            conn.rPush(clientId, new byte[][]{serializedAccessToken});
            if (token.getExpiration() != null) {
                int seconds = token.getExpiresIn();
                conn.expire(accessKey, (long)seconds);
                conn.expire(authKey, (long)seconds);
                conn.expire(authToAccessKey, (long)seconds);
                conn.expire(clientId, (long)seconds);
                conn.expire(approvalKey, (long)seconds);
            }

            OAuth2RefreshToken refreshToken = token.getRefreshToken();
            if (refreshToken != null && refreshToken.getValue() != null) {
                byte[] refresh = this.serialize(token.getRefreshToken().getValue());
                byte[] auth = this.serialize(token.getValue());
                byte[] refreshToAccessKey = this.serializeKey(REFRESH_TO_ACCESS + token.getRefreshToken().getValue());
                conn.set(refreshToAccessKey, auth);
                byte[] accessToRefreshKey = this.serializeKey(ACCESS_TO_REFRESH + token.getValue());
                conn.set(accessToRefreshKey, refresh);
                if (refreshToken instanceof ExpiringOAuth2RefreshToken) {
                    ExpiringOAuth2RefreshToken expiringRefreshToken = (ExpiringOAuth2RefreshToken)refreshToken;
                    Date expiration = expiringRefreshToken.getExpiration();
                    if (expiration != null) {
                        int seconds = Long.valueOf((expiration.getTime() - System.currentTimeMillis()) / 1000L).intValue();
                        conn.expire(refreshToAccessKey, (long)seconds);
                        conn.expire(accessToRefreshKey, (long)seconds);
                    }
                }
            }

            conn.closePipeline();
        } finally {
            conn.close();
        }

    }

    private static String getApprovalKey(OAuth2Authentication authentication) {
        String userName = authentication.getUserAuthentication() == null ? "" : authentication.getUserAuthentication().getName();
        return getApprovalKey(authentication.getOAuth2Request().getClientId(), userName);
    }

    private static String getApprovalKey(String clientId, String userName) {
        return clientId + (userName == null ? "" : ":" + userName);
    }

    /**
     * token 延期
     * @param accessToken
     */
    private void delayExpire(DefaultOAuth2AccessToken accessToken){
        OAuth2Authentication oAuth2Authentication = readAuthentication(accessToken.getValue());
        accessToken.setExpiration(new Date(System.currentTimeMillis() + (oauth2Properties.getAccessTokenTime() * 1000L)));
        storeAccessToken(accessToken,oAuth2Authentication);
    }
    @Override
    public void removeAccessToken(OAuth2AccessToken accessToken) {
        this.removeAccessToken(accessToken.getValue());
    }

    /**
     * 验证 获取token
     * @param tokenValue
     * @return
     */
    @Override
    public OAuth2AccessToken readAccessToken(String tokenValue) {
        byte[] key = this.serializeKey(ACCESS + tokenValue);
        byte[] bytes = null;
        RedisConnection conn = this.getConnection();
        try {
            bytes = conn.get(key);
        } finally {
            conn.close();
        }
        // 延长什么周期
        DefaultOAuth2AccessToken accessToken = this.deserializeAccessToken(bytes);
        if (null != accessToken) {
            delayExpire(accessToken);
        }
        return accessToken;
    }


    public void removeAccessToken(String tokenValue) {
        byte[] accessKey = this.serializeKey(ACCESS + tokenValue);
        byte[] authKey = this.serializeKey(AUTH + tokenValue);
        byte[] accessToRefreshKey = this.serializeKey(ACCESS_TO_REFRESH + tokenValue);
        RedisConnection conn = this.getConnection();

        try {
            conn.openPipeline();
            conn.get(accessKey);
            conn.get(authKey);
            conn.del(new byte[][]{accessKey});
            conn.del(new byte[][]{accessToRefreshKey});
            conn.del(new byte[][]{authKey});
            List<Object> results = conn.closePipeline();
            byte[] access = (byte[])((byte[])results.get(0));
            byte[] auth = (byte[])((byte[])results.get(1));
            OAuth2Authentication authentication = this.deserializeAuthentication(auth);
            if (authentication != null) {
                String key = this.authenticationKeyGenerator.extractKey(authentication);
                byte[] authToAccessKey = this.serializeKey(AUTH_TO_ACCESS + key);
                byte[] unameKey = this.serializeKey(UNAME_TO_ACCESS + getApprovalKey(authentication));
                byte[] clientId = this.serializeKey(CLIENT_ID_TO_ACCESS + authentication.getOAuth2Request().getClientId());
                conn.openPipeline();
                conn.del(new byte[][]{authToAccessKey});
                conn.lRem(unameKey, 1L, access);
                conn.lRem(clientId, 1L, access);
                conn.del(new byte[][]{this.serialize(ACCESS + key)});
                conn.closePipeline();
            }
        } finally {
            conn.close();
        }

    }
    @Override
    public void storeRefreshToken(OAuth2RefreshToken refreshToken, OAuth2Authentication authentication) {
        byte[] refreshKey = this.serializeKey(REFRESH + refreshToken.getValue());
        byte[] refreshAuthKey = this.serializeKey(REFRESH_AUTH + refreshToken.getValue());
        byte[] serializedRefreshToken = this.serialize((Object)refreshToken);
        RedisConnection conn = this.getConnection();

        try {
            conn.openPipeline();
            conn.set(refreshKey, serializedRefreshToken);
            conn.set(refreshAuthKey, this.serialize((Object)authentication));
            if (refreshToken instanceof ExpiringOAuth2RefreshToken) {
                ExpiringOAuth2RefreshToken expiringRefreshToken = (ExpiringOAuth2RefreshToken)refreshToken;
                Date expiration = expiringRefreshToken.getExpiration();
                if (expiration != null) {
                    int seconds = Long.valueOf((expiration.getTime() - System.currentTimeMillis()) / 1000L).intValue();
                    conn.expire(refreshKey, (long)seconds);
                    conn.expire(refreshAuthKey, (long)seconds);
                }
            }

            conn.closePipeline();
        } finally {
            conn.close();
        }

    }
    @Override
    public OAuth2RefreshToken readRefreshToken(String tokenValue) {
        byte[] key = this.serializeKey(REFRESH + tokenValue);
        byte[] bytes = null;
        RedisConnection conn = this.getConnection();
        try {
            bytes = conn.get(key);
        } finally {
            conn.close();
        }

        OAuth2RefreshToken var5 = this.deserializeRefreshToken(bytes);
        return var5;
    }
    @Override
    public void removeRefreshToken(OAuth2RefreshToken refreshToken) {
        this.removeRefreshToken(refreshToken.getValue());
    }

    public void removeRefreshToken(String tokenValue) {
        byte[] refreshKey = this.serializeKey(REFRESH + tokenValue);
        byte[] refreshAuthKey = this.serializeKey(REFRESH_AUTH + tokenValue);
        byte[] refresh2AccessKey = this.serializeKey(REFRESH_TO_ACCESS + tokenValue);
        byte[] access2RefreshKey = this.serializeKey(ACCESS_TO_REFRESH + tokenValue);
        RedisConnection conn = this.getConnection();

        try {
            conn.openPipeline();
            conn.del(new byte[][]{refreshKey});
            conn.del(new byte[][]{refreshAuthKey});
            conn.del(new byte[][]{refresh2AccessKey});
            conn.del(new byte[][]{access2RefreshKey});
            conn.closePipeline();
        } finally {
            conn.close();
        }

    }
    @Override
    public void removeAccessTokenUsingRefreshToken(OAuth2RefreshToken refreshToken) {
        this.removeAccessTokenUsingRefreshToken(refreshToken.getValue());
    }

    private void removeAccessTokenUsingRefreshToken(String refreshToken) {
        byte[] key = this.serializeKey(REFRESH_TO_ACCESS + refreshToken);
        List<Object> results = null;
        RedisConnection conn = this.getConnection();

        try {
            conn.openPipeline();
            conn.get(key);
            conn.del(new byte[][]{key});
            results = conn.closePipeline();
        } finally {
            conn.close();
        }

        if (results != null) {
            byte[] bytes = (byte[])((byte[])results.get(0));
            String accessToken = this.deserializeString(bytes);
            if (accessToken != null) {
                this.removeAccessToken(accessToken);
            }

        }
    }
    @Override
    public Collection<OAuth2AccessToken> findTokensByClientIdAndUserName(String clientId, String userName) {
        byte[] approvalKey = this.serializeKey(UNAME_TO_ACCESS + getApprovalKey(clientId, userName));
        List<byte[]> byteList = null;
        RedisConnection conn = this.getConnection();

        try {
            byteList = conn.lRange(approvalKey, 0L, -1L);
        } finally {
            conn.close();
        }

        if (byteList != null && byteList.size() != 0) {
            List<OAuth2AccessToken> accessTokens = new ArrayList(byteList.size());
            Iterator var7 = byteList.iterator();

            while(var7.hasNext()) {
                byte[] bytes = (byte[])var7.next();
                OAuth2AccessToken accessToken = this.deserializeAccessToken(bytes);
                accessTokens.add(accessToken);
            }

            return Collections.unmodifiableCollection(accessTokens);
        } else {
            return Collections.emptySet();
        }
    }
    @Override
    public Collection<OAuth2AccessToken> findTokensByClientId(String clientId) {
        byte[] key = this.serializeKey(CLIENT_ID_TO_ACCESS + clientId);
        List<byte[]> byteList = null;
        RedisConnection conn = this.getConnection();

        try {
            byteList = conn.lRange(key, 0L, -1L);
        } finally {
            conn.close();
        }

        if (byteList != null && byteList.size() != 0) {
            List<OAuth2AccessToken> accessTokens = new ArrayList(byteList.size());
            Iterator var6 = byteList.iterator();

            while(var6.hasNext()) {
                byte[] bytes = (byte[])var6.next();
                OAuth2AccessToken accessToken = this.deserializeAccessToken(bytes);
                accessTokens.add(accessToken);
            }

            return Collections.unmodifiableCollection(accessTokens);
        } else {
            return Collections.emptySet();
        }
    }
}
