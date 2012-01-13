/*
 *    Copyright 2009-2011 The MyBatis Team
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.binding;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

public class MapperMethod {

  private SqlSession sqlSession;
  private Configuration config;

  private SqlCommandType type;
  private String commandName;

  private Class<?> declaringInterface;
  private Method method;

  private boolean returnsList;
  private boolean returnsMap;
  private boolean returnsVoid;
  private String mapKey;

  private Integer resultHandlerIndex;
  private Integer rowBoundsIndex;
  private List<String> paramNames;
  private List<Integer> paramPositions;

  private boolean hasNamedParameters;

  public MapperMethod(Class<?> declaringInterface, Method method, SqlSession sqlSession) {
    paramNames = new ArrayList<String>();
    paramPositions = new ArrayList<Integer>();
    this.sqlSession = sqlSession;
    this.method = method;
    this.config = sqlSession.getConfiguration();
    this.hasNamedParameters = false;
    this.declaringInterface = declaringInterface;
    setupFields();
    setupMethodSignature();
    setupCommandType();
    validateStatement();
  }

  public Object execute(Object[] args) {
    Object result = null;
    if (SqlCommandType.INSERT == type) {
      Object param = getParam(args);
      result = sqlSession.insert(commandName, param);
    } else if (SqlCommandType.UPDATE == type) {
      Object param = getParam(args);
      result = sqlSession.update(commandName, param);
    } else if (SqlCommandType.DELETE == type) {
      Object param = getParam(args);
      result = sqlSession.delete(commandName, param);
    } else if (SqlCommandType.SELECT == type) {
      if (returnsVoid && resultHandlerIndex != null) {
        executeWithResultHandler(args);
      } else if (returnsList) {
        result = executeForList(args);
      } else if (returnsMap) {
        result = executeForMap(args);
      } else {
        Object param = getParam(args);
        result = sqlSession.selectOne(commandName, param);
      }
    } else {
      throw new BindingException("Unknown execution method for: " + commandName);
    }
    return result;
  }

  private void executeWithResultHandler(Object[] args) {
    Object param = getParam(args);
    if (rowBoundsIndex != null) {
      RowBounds rowBounds = (RowBounds) args[rowBoundsIndex];
      sqlSession.select(commandName, param, rowBounds, (ResultHandler) args[resultHandlerIndex]);
    } else {
      sqlSession.select(commandName, param, (ResultHandler) args[resultHandlerIndex]);
    }
  }

  private <E> List<E> executeForList(Object[] args) {
    List<E> result;
    Object param = getParam(args);
    if (rowBoundsIndex != null) {
      RowBounds rowBounds = (RowBounds) args[rowBoundsIndex];
      result = sqlSession.<E>selectList(commandName, param, rowBounds);
    } else {
      result = sqlSession.<E>selectList(commandName, param);
    }
    return result;
  }

  private <K, V> Map<K, V> executeForMap(Object[] args) {
    Map<K, V> result;
    Object param = getParam(args);
    if (rowBoundsIndex != null) {
      RowBounds rowBounds = (RowBounds) args[rowBoundsIndex];
      result = sqlSession.<K, V>selectMap(commandName, param, mapKey, rowBounds);
    } else {
      result = sqlSession.<K, V>selectMap(commandName, param, mapKey);
    }
    return result;
  }

  private Object getParam(Object[] args) {
    final int paramCount = paramPositions.size();
    if (args == null || paramCount == 0) {
      return null;
    } else if (!hasNamedParameters && paramCount == 1) {
      return args[paramPositions.get(0)];
    } else {
      Map<String, Object> param = new HashMap<String, Object>();
      for (int i = 0; i < paramCount; i++) {
        param.put(paramNames.get(i), args[paramPositions.get(i)]);
        param.put(String.valueOf(i), args[paramPositions.get(i)]); // compatibility with 3.0.x (issue #71)
      }
      return param;
    }
  }

  // Setup //

  private void setupFields() {
    this.commandName = declaringInterface.getName() + "." + method.getName();
  }

  private void setupMethodSignature() {
    if( method.getReturnType().equals(Void.TYPE)){
      returnsVoid = true;
    }
    if (List.class.isAssignableFrom(method.getReturnType())) {
      returnsList = true;
    }
    if (Map.class.isAssignableFrom(method.getReturnType())) {
      final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
      if (mapKeyAnnotation != null) {
        mapKey = mapKeyAnnotation.value();
        returnsMap = true;
      }
    }

    final Class<?>[] argTypes = method.getParameterTypes();
    for (int i = 0; i < argTypes.length; i++) {
      if (RowBounds.class.isAssignableFrom(argTypes[i])) {
        if (rowBoundsIndex == null) {
          rowBoundsIndex = i;
        } else {
          throw new BindingException(method.getName() + " cannot have multiple RowBounds parameters");
        }
      } else if (ResultHandler.class.isAssignableFrom(argTypes[i])) {
        if (resultHandlerIndex == null) {
          resultHandlerIndex = i;
        } else {
          throw new BindingException(method.getName() + " cannot have multiple ResultHandler parameters");
        }
      } else {
        String paramName = "param" + String.valueOf(paramPositions.size() + 1);
        paramName = getParamNameFromAnnotation(i, paramName);
        paramNames.add(paramName);
        paramPositions.add(i);
      }
    }
  }

  private String getParamNameFromAnnotation(int i, String paramName) {
    Object[] paramAnnos = method.getParameterAnnotations()[i];
    for (int j = 0; j < paramAnnos.length; j++) {
      if (paramAnnos[j] instanceof Param) {
        hasNamedParameters = true;
        paramName = ((Param) paramAnnos[j]).value();
      }
    }
    return paramName;
  }

  private void setupCommandType() {
    MappedStatement ms = config.getMappedStatement(commandName);
    type = ms.getSqlCommandType();
    if (type == SqlCommandType.UNKNOWN) {
      throw new BindingException("Unknown execution method for: " + commandName);
    }
  }

  private void validateStatement() {
    try {
      config.getMappedStatement(commandName);
    } catch (Exception e) {
      throw new BindingException("Invalid bound statement (not found): " + commandName, e);
    }
  }

}
