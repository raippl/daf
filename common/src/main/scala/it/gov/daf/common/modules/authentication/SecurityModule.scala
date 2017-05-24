/*
 * Copyright 2017 TEAM PER LA TRASFORMAZIONE DIGITALE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.gov.daf.common.modules.authentication

import java.time.Duration

import com.google.inject.{AbstractModule, Singleton}
import org.ldaptive.auth.{Authenticator, FormatDnResolver, PooledBindAuthenticationHandler}
import org.ldaptive.pool._
import org.ldaptive.{BindConnectionInitializer, ConnectionConfig, Credential, DefaultConnectionFactory}
import org.pac4j.core.client.Clients
import org.pac4j.core.config.Config
import org.pac4j.http.client.direct.{DirectBasicAuthClient, HeaderClient}
import org.pac4j.jwt.config.signature.SecretSignatureConfiguration
import org.pac4j.jwt.credentials.authenticator.JwtAuthenticator
import org.pac4j.ldap.profile.service.LdapProfileService
import org.pac4j.play.http.DefaultHttpActionAdapter
import org.pac4j.play.store.{PlayCacheSessionStore, PlaySessionStore}
import play.api.{Configuration, Environment}


@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements",
    "org.wartremover.warts.Overloading",
    "org.wartremover.warts.Throw"
  )
)
@Singleton
class SecurityModule(environment: Environment, configuration: Configuration) extends AbstractModule {

  private def getLdapAuthenticator = {
    val dnResolver = new FormatDnResolver
    dnResolver.setFormat("uid=%s,cn=users,cn=accounts,dc=daf,dc=gov,dc=it")
    val connectionConfig = new ConnectionConfig
    connectionConfig.setConnectTimeout(Duration.ofMillis(500))
    connectionConfig.setResponseTimeout(Duration.ofMillis(1000))
    connectionConfig.setLdapUrl("ldaps://idm.daf.gov.it:636")
    connectionConfig.setConnectionInitializer(
      new BindConnectionInitializer("uid=admin,cn=users,cn=accounts,dc=daf,dc=gov,dc=it", new Credential("UePh9que")))
    //connectionConfig.setUseSSL(true)
    val connectionFactory = new DefaultConnectionFactory
    connectionFactory.setConnectionConfig(connectionConfig)
    val poolConfig = new PoolConfig
    poolConfig.setMinPoolSize(1)
    poolConfig.setMaxPoolSize(2)
    poolConfig.setValidateOnCheckOut(true)
    poolConfig.setValidateOnCheckIn(true)
    poolConfig.setValidatePeriodically(false)
    val searchValidator = new SearchValidator
    val pruneStrategy = new IdlePruneStrategy
    val connectionPool = new BlockingConnectionPool
    connectionPool.setPoolConfig(poolConfig)
    connectionPool.setBlockWaitTime(Duration.ofMillis(1000))
    connectionPool.setValidator(searchValidator)
    connectionPool.setPruneStrategy(pruneStrategy)
    connectionPool.setConnectionFactory(connectionFactory)
    connectionPool.initialize()
    val pooledConnectionFactory = new PooledConnectionFactory
    pooledConnectionFactory.setConnectionPool(connectionPool)
    val handler = new PooledBindAuthenticationHandler
    handler.setConnectionFactory(pooledConnectionFactory)
    val ldaptiveAuthenticator = new Authenticator
    ldaptiveAuthenticator.setDnResolver(dnResolver)
    ldaptiveAuthenticator.setAuthenticationHandler(handler)
    // pac4j:
    val authenticator = new LdapProfileService(connectionFactory, ldaptiveAuthenticator, "dummy")
    authenticator.setAttributes("")
    authenticator.setUsernameAttribute("uid")
    authenticator
  }

  override def configure(): Unit = {
    bind(classOf[PlaySessionStore]).to(classOf[PlayCacheSessionStore])

    val directBasicAuthClient = new DirectBasicAuthClient(getLdapAuthenticator)

    val secret = configuration.getString("pac4j.jwt_secret").fold[String](throw new Exception("missing secret"))(identity)

    val jwtAuthenticator = new JwtAuthenticator()
    jwtAuthenticator.addSignatureConfiguration(new SecretSignatureConfiguration(secret))

    val parameterClient = new HeaderClient("Authorization", "Bearer ", jwtAuthenticator)

    val config = new Config(new Clients(directBasicAuthClient, parameterClient))

    config.setHttpActionAdapter(new DefaultHttpActionAdapter())

    bind(classOf[Config]).toInstance(config)
    bind(classOf[JwtAuthenticator]).toInstance(jwtAuthenticator)
  }
}