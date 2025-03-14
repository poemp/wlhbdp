// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "exprs/function_context.h"

#include <iostream>

#include "exprs/agg/java_udaf_function.h"
#include "runtime/runtime_state.h"

namespace starrocks {

static const int MAX_WARNINGS = 1000;

FunctionContext* FunctionContext::create_context(RuntimeState* state, MemPool* pool,
                                                 const FunctionContext::TypeDesc& return_type,
                                                 const std::vector<FunctionContext::TypeDesc>& arg_types) {
    auto* ctx = new FunctionContext();
    ctx->_state = state;
    ctx->_mem_pool = pool;
    ctx->_return_type = return_type;
    ctx->_arg_types = arg_types;
    ctx->_jvm_udaf_ctxs = std::make_unique<JavaUDAFContext>();
    return ctx;
}

FunctionContext* FunctionContext::create_context(RuntimeState* state, MemPool* pool,
                                                 const FunctionContext::TypeDesc& return_type,
                                                 const std::vector<FunctionContext::TypeDesc>& arg_types,
                                                 const std::vector<bool>& is_asc_order,
                                                 const std::vector<bool>& nulls_first) {
    auto* ctx = new FunctionContext();
    ctx->_state = state;
    ctx->_mem_pool = pool;
    ctx->_return_type = return_type;
    ctx->_arg_types = arg_types;
    ctx->_jvm_udaf_ctxs = std::make_unique<JavaUDAFContext>();
    ctx->_is_asc_order = is_asc_order;
    ctx->_nulls_first = nulls_first;
    return ctx;
}

FunctionContext* FunctionContext::create_test_context() {
    auto* context = new FunctionContext();
    context->_state = nullptr;
    return context;
}

FunctionContext* FunctionContext::create_test_context(std::vector<TypeDesc>&& arg_types, const TypeDesc& return_type) {
    FunctionContext* context = FunctionContext::create_test_context();
    context->_arg_types = std::move(arg_types);
    context->_return_type = return_type;
    return context;
}

FunctionContext::FunctionContext()
        : _state(nullptr), _num_warnings(0), _thread_local_fn_state(nullptr), _fragment_local_fn_state(nullptr) {}

FunctionContext::~FunctionContext() = default;

FunctionContext* FunctionContext::clone(MemPool* pool) {
    FunctionContext* new_context = create_context(_state, pool, _return_type, _arg_types);

    new_context->_constant_columns = _constant_columns;
    new_context->_fragment_local_fn_state = _fragment_local_fn_state;
    return new_context;
}

int FunctionContext::get_num_args() const {
    return _arg_types.size();
}

int FunctionContext::get_num_constant_columns() const {
    return _constant_columns.size();
}

bool FunctionContext::is_constant_column(int i) const {
    if (i < 0 || i >= _constant_columns.size()) {
        return false;
    }

    return _constant_columns[i] && _constant_columns[i]->is_constant();
}

bool FunctionContext::is_notnull_constant_column(int i) const {
    if (i < 0 || i >= _constant_columns.size()) {
        return false;
    }

    auto& col = _constant_columns[i];
    return col && col->is_constant() && !col->is_null(0);
}

starrocks::ColumnPtr FunctionContext::get_constant_column(int i) const {
    if (i < 0 || i >= _constant_columns.size()) {
        return nullptr;
    }

    return _constant_columns[i];
}

const FunctionContext::TypeDesc& FunctionContext::get_return_type() const {
    return _return_type;
}

void* FunctionContext::get_function_state(FunctionStateScope scope) const {
    switch (scope) {
    case THREAD_LOCAL:
        return _thread_local_fn_state;
    case FRAGMENT_LOCAL:
        return _fragment_local_fn_state;
    default:
        // TODO: signal error somehow
        return nullptr;
    }
}

void FunctionContext::set_error(const char* error_msg, const bool is_udf) {
    std::lock_guard<std::mutex> lock(_error_msg_mutex);
    if (_error_msg.empty()) {
        _error_msg = error_msg;
        std::stringstream ss;
        ss << (is_udf ? "UDF ERROR: " : "") << error_msg;
        if (_state != nullptr) {
            _state->set_process_status(ss.str());
        }
    }
}

bool FunctionContext::has_error() const {
    std::lock_guard<std::mutex> lock(_error_msg_mutex);
    return !_error_msg.empty();
}

const char* FunctionContext::error_msg() const {
    std::lock_guard<std::mutex> lock(_error_msg_mutex);
    if (!_error_msg.empty()) {
        return _error_msg.c_str();
    } else {
        return nullptr;
    }
}

void FunctionContext::set_function_state(FunctionStateScope scope, void* ptr) {
    switch (scope) {
    case THREAD_LOCAL:
        _thread_local_fn_state = ptr;
        break;
    case FRAGMENT_LOCAL:
        _fragment_local_fn_state = ptr;
        break;
    default:
        std::stringstream ss;
        ss << "Unknown FunctionStateScope: " << scope;
        set_error(ss.str().c_str());
    }
}

bool FunctionContext::add_warning(const char* warning_msg) {
    if (_num_warnings++ >= MAX_WARNINGS) {
        return false;
    }

    std::stringstream ss;
    ss << "UDF WARNING: " << warning_msg;

    if (_state != nullptr) {
        return _state->log_error(ss.str());
    } else {
        std::cerr << ss.str() << std::endl;
        return true;
    }
}

const FunctionContext::TypeDesc* FunctionContext::get_arg_type(int arg_idx) const {
    if (arg_idx < 0 || arg_idx >= _arg_types.size()) {
        return nullptr;
    }
    return &_arg_types[arg_idx];
}

} // namespace starrocks
