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
package org.neo4j.kernel.impl.newapi;

import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.IOUtils;
import org.neo4j.kernel.api.index.IndexProgressor;
import org.neo4j.values.storable.Value;

class RelationshipSecurityFilter implements IndexProgressor.EntityValueClient, IndexProgressor
{
    private final EntityValueClient target;
    private final Read read;
    private final RelationshipScanCursor relCursor;
    private IndexProgressor progressor;

    RelationshipSecurityFilter( EntityValueClient target, RelationshipScanCursor relCursor, Read read )
    {
        this.target = target;
        this.read = read;
        this.relCursor = relCursor;
    }

    @Override
    public boolean next()
    {
        return progressor.next();
    }

    @Override
    public void close()
    {
        IOUtils.close( RuntimeException::new, relCursor, progressor );
    }

    @Override
    public void initialize( IndexDescriptor descriptor, IndexProgressor progressor, IndexQuery[] query, IndexOrder indexOrder, boolean needsValues,
            boolean indexIncludesTransactionState )
    {
        this.progressor = progressor;
        target.initialize( descriptor, this, query, indexOrder, needsValues, indexIncludesTransactionState );
    }

    @Override
    public boolean acceptEntity( long reference, float score, Value... values )
    {
        read.singleRelationship( reference, relCursor );
        return relCursor.next() && target.acceptEntity( reference, score, values );
    }

    @Override
    public boolean needsValues()
    {
        return target.needsValues();
    }
}