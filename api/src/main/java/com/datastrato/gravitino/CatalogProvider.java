/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datastrato.gravitino;

import com.datastrato.gravitino.annotation.Evolving;

/**
 * A Catalog provider is a class that provides a short name for a catalog. This short name is used
 * when creating a catalog.
 */
@Evolving
public interface CatalogProvider {

  /**
   * The string that represents the catalog that this provider uses. This is overridden by children
   * to provide a nice alias for the catalog.
   *
   * @return The string that represents the catalog that this provider uses.
   */
  String shortName();
}
