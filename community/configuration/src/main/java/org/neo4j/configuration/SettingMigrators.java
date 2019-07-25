/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.connectors.HttpConnector;
import org.neo4j.configuration.connectors.HttpsConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.logging.Log;
import org.neo4j.values.storable.CoordinateReferenceSystem;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.join;
import static org.neo4j.configuration.GraphDatabaseSettings.default_advertised_address;
import static org.neo4j.configuration.GraphDatabaseSettings.default_database;
import static org.neo4j.configuration.GraphDatabaseSettings.default_listen_address;
import static org.neo4j.configuration.SettingValueParsers.LIST_SEPARATOR;
import static org.neo4j.configuration.SettingValueParsers.SOCKET_ADDRESS;

public final class SettingMigrators
{
    private SettingMigrators()
    {
    }

    @ServiceProvider
    public static class ActiveDatabaseMigrator implements SettingMigrator
    {
        @Override
        public void migrate( Map<String,String> settings, Map<String,String> defaultValues, Log log )
        {
            String deprecatedValue = settings.remove( "dbms.active_database" );
            if ( isNotBlank( deprecatedValue ) )
            {
                log.warn( "Use of deprecated setting dbms.active_database. Replaced by %s.", default_database.name() );
                settings.putIfAbsent( default_database.name(), deprecatedValue );
            }
        }
    }

    @ServiceProvider
    public static class CrsConfigMigrator implements SettingMigrator
    {
        private static final String PREFIX = "unsupported.dbms.db.spatial.crs";
        private static final Pattern oldConnector = Pattern.compile( "^unsupported\\.dbms\\.db\\.spatial\\.crs\\.([^.]+)\\.(min|max)\\.([xyz])$");

        @Override
        public void migrate( Map<String,String> values, Map<String,String> defaultValues, Log log )
        {
            List<String> oldCrs = new ArrayList<>();
            Map<String, List<String>> crsValues = new HashMap<>();
            values.forEach( ( setting, value ) ->
            {
                Matcher matcher = oldConnector.matcher( setting );
                if ( matcher.find() )
                {
                    String crsName = matcher.group( 1 );
                    String crsPlusSetting = format( "%s.%s", crsName, matcher.group( 2 ) );
                    CoordinateReferenceSystem crs = CoordinateReferenceSystem.byName( crsName );
                    List<String> valueList = crsValues.computeIfAbsent( crsPlusSetting,
                            s -> new ArrayList<>( Collections.nCopies( crs.getDimension(), Double.toString( Double.NaN ) ) ) );
                    valueList.set( matcher.group( 3 ).charAt( 0 ) - 'x' , value );
                    oldCrs.add( setting );
                }
            } );

            oldCrs.forEach( setting -> {
                values.remove( setting );
                log.warn( "Use of deprecated setting %s.", setting );
            } );
            crsValues.forEach( ( name, valueList ) -> {
                String setting = format( "%s.%s", PREFIX, name );
                String value = join( valueList, LIST_SEPARATOR );
                values.putIfAbsent( setting, value );
                log.warn( "Settings migrated to %s = %s", setting, value );
            } );
        }
    }

    @ServiceProvider
    public static class ConnectorMigrator implements SettingMigrator
    {
        private static final Pattern oldConnector = Pattern.compile( "^dbms\\.connector\\.([^.]+)\\.([^.]+)$");
        private static final String ANY_CONNECTOR = "bolt|http|https";

        @Override
        public void migrate( Map<String,String> values, Map<String,String> defaultValues, Log log )
        {
            migrateOldConnectors( values, log );
            migrateConnectorAddresses( values, defaultValues, log );
        }

        private static void migrateOldConnectors( Map<String,String> values, Log log )
        {
            Map<String, Matcher> oldConnectors = new HashMap<>();
            values.forEach( ( setting, value ) ->
            {
                Matcher matcher = oldConnector.matcher( setting );
                if ( matcher.find() )
                {
                    oldConnectors.put( setting, matcher );
                }
            } );

            oldConnectors.forEach( ( setting, matcher ) -> {
                String settingName = matcher.group( 2 );
                String id = matcher.group( 1 );
                if ( id.matches( ANY_CONNECTOR ) )
                {
                    if ( Objects.equals( "type", settingName ) )
                    {
                        values.remove( setting );
                        log.warn( "Use of deprecated setting %s. Type is no longer required", setting );
                    }
                }
                else
                {
                    values.remove( setting );
                    log.warn( "Use of deprecated setting %s. No longer supports multiple connectors. Setting discarded.", setting );
                }
            } );
        }

        private static void migrateConnectorAddresses( Map<String,String> values, Map<String,String> defValues,  Log log )
        {
            migrateAdvertisedAddressInheritanceChange( values, defValues, log, BoltConnector.listen_address.name(), BoltConnector.advertised_address.name() );
            migrateAdvertisedAddressInheritanceChange( values, defValues, log, HttpConnector.listen_address.name(), HttpConnector.advertised_address.name() );
            migrateAdvertisedAddressInheritanceChange( values, defValues, log, HttpsConnector.listen_address.name(), HttpsConnector.advertised_address.name() );
        }
    }

    @ServiceProvider
    public static class DefaultAddressMigrator implements SettingMigrator
    {
        private static final Map<String,String> SETTINGS_TO_MIGRATE = Map.of(
                "dbms.connectors.default_listen_address", default_listen_address.name(),
                "dbms.connectors.default_advertised_address", default_advertised_address.name()
        );

        @Override
        public void migrate( Map<String,String> values, Map<String,String> defaultValues, Log log )
        {
            SETTINGS_TO_MIGRATE.forEach( ( oldSetting, newSetting ) -> {
                String value = values.remove( oldSetting );
                if ( isNotBlank( value ) )
                {
                    log.warn( "Use of deprecated setting %s. It is replaced by %s", oldSetting, newSetting );
                    values.putIfAbsent( newSetting, value );
                }
            } );
        }
    }

    public static void migrateAdvertisedAddressInheritanceChange( Map<String,String> values, Map<String,String> defaultValues,
            Log log, String listenAddress, String advertisedAddress )
    {
        String listenValue = values.get( listenAddress );
        if ( isNotBlank( listenValue ) )
        {
            String advertisedValue = values.get( advertisedAddress );
            boolean advertisedAlreadyHasPort = false;
            try
            {
                if ( isNotBlank( advertisedValue ) )
                {
                    advertisedAlreadyHasPort = SOCKET_ADDRESS.parse( advertisedValue ).getPort() >= 0;
                }
            }
            catch ( RuntimeException e )
            {
                // If we cant parse the advertised address we act as if it has no port specified
                // If invalid hostname config will report the error
            }

            if ( !advertisedAlreadyHasPort )
            {
                try
                {
                    int port = SOCKET_ADDRESS.parse( listenValue ).getPort();
                    if ( port >= 0 ) //valid port on listen, and none on advertised, migrate!
                    {
                        SocketAddress newAdvertised = new SocketAddress( advertisedValue, port );
                        log.warn( "Use of deprecated setting port propagation. port %s is migrated from %s to %s.", port, listenAddress, advertisedAddress );
                        defaultValues.put( advertisedAddress, newAdvertised.toString() );
                    }
                }
                catch ( RuntimeException e )
                {
                    //If we cant parse the listen address we have no information on how to proceed with the migration
                    // The config will handle the error later
                }
            }
        }
    }
}