// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <stdint.h>

#include <string>

#include "common/status.h"

namespace doris {

class RuntimeState;

// abstract class of the result writer
class ResultWriter {
public:
    ResultWriter() = default;
    ResultWriter(bool output_object_data) : _output_object_data(output_object_data) {}
    virtual ~ResultWriter() = default;

    virtual Status init(RuntimeState* state) = 0;

    virtual Status close() = 0;

    virtual int64_t get_written_rows() const { return _written_rows; }

    virtual bool output_object_data() const { return _output_object_data; }

    void set_output_object_data(bool output_object_data) {
        _output_object_data = output_object_data;
    }

    static const std::string NULL_IN_CSV;
    virtual void set_header_info(const std::string& header_type, const std::string& header) {
        _header_type = header_type;
        _header = header;
    }

protected:
    int64_t _written_rows = 0; // number of rows written
    bool _output_object_data = false;
    std::string _header_type;
    std::string _header;
};

} // namespace doris
