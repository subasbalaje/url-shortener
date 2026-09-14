package com.sdlc.shortener.config;

import com.sdlc.shortener.service.CacheService;
import com.sdlc.shortener.service.CodecService;
import com.sdlc.shortener.service.ValidationService;
import com.sdlc.shortener.store.LinkStore;
import com.sdlc.shortener.store.SqliteLinkStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/** Bean wiring, and startup schema creation. */
@Configuration
public class AppConfig {

    @Value("${shortener.db-path:shortener.db}")
    private String dbPath;

    @Value("${shortener.own-host:localhost}")
    private String ownHost;

    @Bean
    public DataSource dataSource() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }

    @Bean
    @Primary
    public LinkStore linkStore(DataSource dataSource) {
        SqliteLinkStore store = new SqliteLinkStore(dataSource);
        store.initSchema(); // first-time setup, not a migration -- see V1__init.sql
        return store;
    }

    @Bean
    public CodecService codecService(LinkStore linkStore) {
        return new CodecService(linkStore::exists);
    }

    @Bean
    public ValidationService validationService() {
        return new ValidationService(ValidationService::resolveViaDns, ownHost);
    }

    @Bean
    public CacheService cacheService() {
        return new CacheService(Duration.ofMinutes(5), 10_000);
    }
}
