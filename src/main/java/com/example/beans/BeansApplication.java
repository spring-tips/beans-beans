package com.example.beans;

import org.postgresql.Driver;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Collection;

@SpringBootApplication
public class BeansApplication {

    static ApplicationContext zero() {
        var factory = new DefaultListableBeanFactory();

        var driverBeanDefinition = new RootBeanDefinition(Driver.class);
        factory.registerBeanDefinition("driver", driverBeanDefinition);

        var dataSourceBeanDefinition = new RootBeanDefinition(SimpleDriverDataSource.class);
        dataSourceBeanDefinition.getConstructorArgumentValues().addGenericArgumentValue(new RuntimeBeanReference("driver"));
        dataSourceBeanDefinition.getConstructorArgumentValues().addGenericArgumentValue("jdbc:postgresql://localhost/mydatabase");
        dataSourceBeanDefinition.getConstructorArgumentValues().addGenericArgumentValue("myuser");
        dataSourceBeanDefinition.getConstructorArgumentValues().addGenericArgumentValue("secret");

        var jdbcClientBeanDefinition = new RootBeanDefinition(JdbcClient.class);
        jdbcClientBeanDefinition.setFactoryMethodName("create");
        jdbcClientBeanDefinition.getConstructorArgumentValues().addGenericArgumentValue(dataSourceBeanDefinition);
        factory.registerBeanDefinition("jdbcClient", jdbcClientBeanDefinition);

        var customerServiceBeanDefinition = new RootBeanDefinition(CustomerService.class);
        customerServiceBeanDefinition.getConstructorArgumentValues().addGenericArgumentValue(new RuntimeBeanReference("jdbcClient"));
        factory.registerBeanDefinition("customerService", customerServiceBeanDefinition);

        var gac = new GenericApplicationContext(factory);
        gac.refresh();
        return exercise(gac);
    }

    static ApplicationContext one() {
        return exercise(new ClassPathXmlApplicationContext("beans-1.xml"));
    }

    static ApplicationContext two() {
        return exercise(new ClassPathXmlApplicationContext("beans-2.xml"));
    }

    static ApplicationContext three() {
        return exercise(new ClassPathXmlApplicationContext("beans-3.xml"));
    }

    static ApplicationContext four() {
        return exercise(new ClassPathXmlApplicationContext("beans-4.xml"));
    }

    static ApplicationContext five() {
        System.setProperty("spring.profiles.active", "five");
        var applicationContext = new AnnotationConfigApplicationContext(FiveApplicationConfiguration.class);
        return exercise(applicationContext);
    }

    // demonstrate functional configuration
    static ApplicationContext six() {
        var gac = new GenericApplicationContext();
        gac.registerBean("customerService", CustomerService.class);
        gac.registerBean("driver", Driver.class);
        gac.registerBean("environment", Environment.class, () -> {
            try {
                var se = new StandardEnvironment();
                se.getPropertySources().addFirst(new ResourcePropertySource("classpath:application.properties"));
                return se;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        gac.registerBean("simpleDriverDatasource", DataSource.class, () -> {
            var environment = gac.getBean(Environment.class);
            return new SimpleDriverDataSource(gac.getBean(Driver.class),
                    environment.getProperty("spring.datasource.url"),
                    environment.getProperty("spring.datasource.username"),
                    environment.getProperty("spring.datasource.password"));
        });
        gac.registerBean("jdbcClient", JdbcClient.class, () -> JdbcClient.create(gac.getBean(DataSource.class)));
        gac.registerBean("customerService", CustomerService.class, () -> new CustomerService(gac.getBean(JdbcClient.class)));
        gac.refresh();
        return exercise(gac);

    }

    // delete the FiveApplicationConfiguration or put it behind a profile as it is redundant
    static ApplicationContext seven(String [] args ) {
        System.setProperty("spring.profiles.active", "seven");
        var applicationContext = SpringApplication.run(BeansApplication.class, args);
        return exercise(applicationContext);
    }

    public static void main(String[] args) {
        var start = System.nanoTime();
        seven(args);
        var stop = System.nanoTime();
        var seconds = (stop - start) / 1_000_000_000.0;
        System.out.println("seconds: " + seconds);
    }

    private static ApplicationContext exercise(ApplicationContext applicationContext) {
        var customerService = applicationContext.getBean(CustomerService.class);
        var customers = customerService.getCustomers();
        for (var c : customers)
            System.out.println(c.toString());
        return applicationContext;
    }
}

@Configuration
@Profile("five")
@ComponentScan
@PropertySource("classpath:/application.properties")
class FiveApplicationConfiguration {

    FiveApplicationConfiguration() {
        System.out.println("running for five");
    }

    @Bean
    DataSource dataSource(Environment environment) {
        var driver = new org.postgresql.Driver();
        return new SimpleDriverDataSource(driver, environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"));
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

}

@Service
class CustomerService implements InitializingBean {

    private final JdbcClient jdbcClient;

    CustomerService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Collection<Customer> getCustomers() {
        return this
                .jdbcClient
                .sql("select * from customers")
                .query((rs, rowNum) -> new Customer(rs.getInt("id"), rs.getString("name")))
                .list();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        System.out.println("afterPropertiesSet");
        Assert.notNull(this.jdbcClient, "the jdbcClient must not be null");
    }
}

record Customer(Integer id, String name) {
}

// beans-3
class JdbcClientFactoryBean implements FactoryBean<JdbcClient> {

    private String url, username, password;

    public void setUrl(String url) {
        this.url = url;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public JdbcClient getObject() throws Exception {
        var driver = new org.postgresql.Driver();
        var jc = new SimpleDriverDataSource(driver, this.url, this.username, this.password);
        return JdbcClient.create(jc);
    }

    @Override
    public Class<?> getObjectType() {
        return JdbcClient.class;
    }
}

