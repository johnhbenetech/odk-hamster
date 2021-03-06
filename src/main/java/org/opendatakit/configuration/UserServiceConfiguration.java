/* Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.configuration;

import java.beans.PropertyVetoException;

import org.opendatakit.context.CallingContext;
import org.opendatakit.context.CallingContextImpl;
import org.opendatakit.persistence.exception.ODKDatastoreException;
import org.opendatakit.security.Realm;
import org.opendatakit.security.UserService;
import org.opendatakit.security.spring.RoleHierarchyImpl;
import org.opendatakit.security.spring.UserServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;

@Configuration
@Profile("default")
@ComponentScan(basePackages = {"org.opendatakit"})
public class UserServiceConfiguration {

  @Autowired
  private DataConfiguration dataConfiguration;

  @Value("/")
  private String servletPath;

  @Value("${security.server.realm.realmString:opendatakit.org ODK 2.0 Server}")
  private String realmString;

  @Value("${security.server.hostname:localhost}")
  private String hostname;

  @Value("${security.server.superUserUsername:admin}")
  private String superUserUsername;

  @Value("")
  private String webApplicationBase;

  @Bean
  public Realm realm() {
    Realm realm = new Realm();
    realm.setRealmString(realmString);
    realm.setHostname(hostname);
    return realm;
  }
  
  @Bean
  public UserService userService() throws ODKDatastoreException, PropertyVetoException {
    UserServiceImpl userServiceImpl = new UserServiceImpl();
    userServiceImpl.setRealm(realm());
    userServiceImpl.setDatastore(dataConfiguration.datastore());
    userServiceImpl.setSuperUserUsername(superUserUsername);
   return userServiceImpl;
  }

  @Bean
  public RoleHierarchy hierarchicalRoleRelationships()
      throws ODKDatastoreException, PropertyVetoException {
    RoleHierarchyImpl roleHierarchyImpl = new RoleHierarchyImpl();
    roleHierarchyImpl.setDatastore(dataConfiguration.datastore());
    roleHierarchyImpl.setUserService(userService());
     return roleHierarchyImpl;
  }

  @Bean
  public CallingContext callingContext() throws ODKDatastoreException, PropertyVetoException {
    CallingContextImpl callingContextImpl = new CallingContextImpl(dataConfiguration.datastore(),
        userService(), hierarchicalRoleRelationships(),
        servletPath, false);
    // This is probably not necessary, it's only used by the GUI in aggregate
    //ServerPreferencesProperties.setOdkTablesEnabled(callingContextImpl, true);
    return callingContextImpl;
  }
}
