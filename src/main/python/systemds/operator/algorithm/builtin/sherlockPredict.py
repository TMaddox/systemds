# -------------------------------------------------------------
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# -------------------------------------------------------------

# Autogenerated By   : src/main/python/generator/generator.py
# Autogenerated From : scripts/builtin/sherlockPredict.dml

from typing import Dict, Iterable

from systemds.operator import OperationNode, Matrix
from systemds.script_building.dag import OutputType
from systemds.utils.consts import VALID_INPUT_TYPES

def sherlockPredict(X: OperationNode, cW1: OperationNode, cb1: OperationNode, cW2: OperationNode, cb2: OperationNode, cW3: OperationNode, cb3: OperationNode, wW1: OperationNode, wb1: OperationNode, wW2: OperationNode, wb2: OperationNode, wW3: OperationNode, wb3: OperationNode, pW1: OperationNode, pb1: OperationNode, pW2: OperationNode, pb2: OperationNode, pW3: OperationNode, pb3: OperationNode, sW1: OperationNode, sb1: OperationNode, sW2: OperationNode, sb2: OperationNode, sW3: OperationNode, sb3: OperationNode, fW1: OperationNode, fb1: OperationNode, fW2: OperationNode, fb2: OperationNode, fW3: OperationNode, fb3: OperationNode) -> Matrix:
    
    
    X._check_matrix_op()
    cW1._check_matrix_op()
    cb1._check_matrix_op()
    cW2._check_matrix_op()
    cb2._check_matrix_op()
    cW3._check_matrix_op()
    cb3._check_matrix_op()
    wW1._check_matrix_op()
    wb1._check_matrix_op()
    wW2._check_matrix_op()
    wb2._check_matrix_op()
    wW3._check_matrix_op()
    wb3._check_matrix_op()
    pW1._check_matrix_op()
    pb1._check_matrix_op()
    pW2._check_matrix_op()
    pb2._check_matrix_op()
    pW3._check_matrix_op()
    pb3._check_matrix_op()
    sW1._check_matrix_op()
    sb1._check_matrix_op()
    sW2._check_matrix_op()
    sb2._check_matrix_op()
    sW3._check_matrix_op()
    sb3._check_matrix_op()
    fW1._check_matrix_op()
    fb1._check_matrix_op()
    fW2._check_matrix_op()
    fb2._check_matrix_op()
    fW3._check_matrix_op()
    fb3._check_matrix_op()
    params_dict = {'X':X, 'cW1':cW1, 'cb1':cb1, 'cW2':cW2, 'cb2':cb2, 'cW3':cW3, 'cb3':cb3, 'wW1':wW1, 'wb1':wb1, 'wW2':wW2, 'wb2':wb2, 'wW3':wW3, 'wb3':wb3, 'pW1':pW1, 'pb1':pb1, 'pW2':pW2, 'pb2':pb2, 'pW3':pW3, 'pb3':pb3, 'sW1':sW1, 'sb1':sb1, 'sW2':sW2, 'sb2':sb2, 'sW3':sW3, 'sb3':sb3, 'fW1':fW1, 'fb1':fb1, 'fW2':fW2, 'fb2':fb2, 'fW3':fW3, 'fb3':fb3}
    return Matrix(X.sds_context, 'sherlockPredict', named_input_nodes=params_dict)


    