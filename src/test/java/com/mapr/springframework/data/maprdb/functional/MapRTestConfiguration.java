package com.mapr.springframework.data.maprdb.functional;

import com.mapr.springframework.data.maprdb.config.AbstractMapRConfiguration;
import com.mapr.springframework.data.maprdb.repository.config.EnableMapRRepository;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableMapRRepository
public class MapRTestConfiguration extends AbstractMapRConfiguration {

    public final static String DB_NAME = "test";

    @Override
    protected String getDatabaseName() {
        return DB_NAME;
    }

    @Override
    protected String getHost() {
        return "node1";
    }

    @Override
    protected String getUsername() {
        return "";
    }

    @Override
    protected String getPassword() {
        return "";
    }

}
