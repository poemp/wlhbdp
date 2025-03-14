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

#include <fmt/format.h>
#include <gen_cpp/olap_file.pb.h>
#include <stddef.h>
#include <stdint.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <roaring/roaring.hh>
#include <string>
#include <unordered_set>
#include <vector>

#include "common/status.h"
#include "io/fs/file_reader_writer_fwd.h"
#include "olap/olap_common.h"
#include "olap/rowset/rowset.h"
#include "olap/rowset/rowset_meta.h"
#include "olap/rowset/rowset_writer.h"
#include "olap/rowset/rowset_writer_context.h"
#include "segcompaction.h"
#include "segment_v2/segment.h"
#include "util/spinlock.h"

namespace doris {
namespace vectorized {
class Block;
} // namespace vectorized

namespace segment_v2 {
class SegmentWriter;
} // namespace segment_v2

using SegCompactionCandidates = std::vector<segment_v2::SegmentSharedPtr>;
using SegCompactionCandidatesSharedPtr = std::shared_ptr<SegCompactionCandidates>;
namespace vectorized::schema_util {
class LocalSchemaChangeRecorder;
}

class BetaRowsetWriter : public RowsetWriter {
    friend class SegcompactionWorker;

public:
    BetaRowsetWriter();

    ~BetaRowsetWriter() override;

    Status init(const RowsetWriterContext& rowset_writer_context) override;

    Status add_block(const vectorized::Block* block) override;

    // add rowset by create hard link
    Status add_rowset(RowsetSharedPtr rowset) override;

    Status add_rowset_for_linked_schema_change(RowsetSharedPtr rowset) override;

    Status flush() override;

    // Return the file size flushed to disk in "flush_size"
    // This method is thread-safe.
    Status flush_single_memtable(const vectorized::Block* block, int64_t* flush_size,
                                 const FlushContext* ctx = nullptr) override;

    RowsetSharedPtr build() override;

    // build a tmp rowset for load segment to calc delete_bitmap
    // for this segment
    RowsetSharedPtr build_tmp() override;

    RowsetSharedPtr manual_build(const RowsetMetaSharedPtr& rowset_meta) override;

    Version version() override { return _context.version; }

    int64_t num_rows() const override { return _raw_num_rows_written; }

    RowsetId rowset_id() override { return _context.rowset_id; }

    RowsetTypePB type() const override { return RowsetTypePB::BETA_ROWSET; }

    Status get_segment_num_rows(std::vector<uint32_t>* segment_num_rows) const override {
        std::lock_guard<SpinLock> l(_lock);
        *segment_num_rows = _segment_num_rows;
        return Status::OK();
    }

    int32_t allocate_segment_id() override { return _next_segment_id.fetch_add(1); };

    // Maybe modified by local schema change
    vectorized::schema_util::LocalSchemaChangeRecorder* mutable_schema_change_recorder() override {
        return _context.schema_change_recorder.get();
    }

    SegcompactionWorker& get_segcompaction_worker() { return _segcompaction_worker; }

    Status flush_segment_writer_for_segcompaction(
            std::unique_ptr<segment_v2::SegmentWriter>* writer, uint64_t index_size,
            KeyBoundsPB& key_bounds);

    bool is_doing_segcompaction() const override { return _is_doing_segcompaction; }

    Status wait_flying_segcompaction() override;

private:
    Status _do_add_block(const vectorized::Block* block,
                         std::unique_ptr<segment_v2::SegmentWriter>* segment_writer,
                         size_t row_offset, size_t input_row_num);
    Status _add_block(const vectorized::Block* block,
                      std::unique_ptr<segment_v2::SegmentWriter>* writer,
                      const FlushContext* flush_ctx = nullptr);

    Status _do_create_segment_writer(std::unique_ptr<segment_v2::SegmentWriter>* writer,
                                     bool is_segcompaction, int64_t begin, int64_t end,
                                     const FlushContext* ctx = nullptr);
    Status _create_segment_writer(std::unique_ptr<segment_v2::SegmentWriter>* writer,
                                  const FlushContext* ctx = nullptr);
    Status _flush_segment_writer(std::unique_ptr<segment_v2::SegmentWriter>* writer,
                                 int64_t* flush_size = nullptr);
    void _build_rowset_meta(std::shared_ptr<RowsetMeta> rowset_meta);
    Status _segcompaction_if_necessary();
    Status _segcompaction_ramaining_if_necessary();
    Status _load_noncompacted_segments(std::vector<segment_v2::SegmentSharedPtr>* segments,
                                       size_t num);
    Status _find_longest_consecutive_small_segment(SegCompactionCandidatesSharedPtr segments);
    Status _get_segcompaction_candidates(SegCompactionCandidatesSharedPtr& segments, bool is_last);
    bool _is_segcompacted() { return (_num_segcompacted > 0) ? true : false; }

    bool _check_and_set_is_doing_segcompaction();

    void _build_rowset_meta_with_spec_field(RowsetMetaSharedPtr rowset_meta,
                                            const RowsetMetaSharedPtr& spec_rowset_meta);
    bool _is_segment_overlapping(const std::vector<KeyBoundsPB>& segments_encoded_key_bounds);
    void _clear_statistics_for_deleting_segments_unsafe(uint64_t begin, uint64_t end);
    Status _rename_compacted_segments(int64_t begin, int64_t end);
    Status _rename_compacted_segment_plain(uint64_t seg_id);
    Status _rename_compacted_indices(int64_t begin, int64_t end, uint64_t seg_id);

    void set_segment_start_id(int32_t start_id) override { _segment_start_id = start_id; }

protected:
    RowsetWriterContext _context;
    std::shared_ptr<RowsetMeta> _rowset_meta;

    std::atomic<int32_t> _next_segment_id; // the next available segment_id (offset),
                                           // also the numer of allocated segments
    std::atomic<int32_t> _num_segment;     // number of consecutive flushed segments
    roaring::Roaring _segment_set;         // bitmap set to record flushed segment id
    std::mutex _segment_set_mutex;         // mutex for _segment_set
    int32_t _segment_start_id; //basic write start from 0, partial update may be different
    std::atomic<int32_t> _segcompacted_point; // segemnts before this point have
                                              // already been segment compacted
    std::atomic<int32_t> _num_segcompacted;   // index for segment compaction
    /// When flushing the memtable in the load process, we do not use this writer but an independent writer.
    /// Because we want to flush memtables in parallel.
    /// In other processes, such as merger or schema change, we will use this unified writer for data writing.
    std::unique_ptr<segment_v2::SegmentWriter> _segment_writer;

    mutable SpinLock _lock; // protect following vectors.
    // record rows number of every segment already written, using for rowid
    // conversion when compaction in unique key with MoW model
    std::vector<uint32_t> _segment_num_rows;
    std::vector<io::FileWriterPtr> _file_writers;
    // for unique key table with merge-on-write
    std::vector<KeyBoundsPB> _segments_encoded_key_bounds;

    // counters and statistics maintained during add_rowset
    std::atomic<int64_t> _num_rows_written;
    std::atomic<int64_t> _total_data_size;
    std::atomic<int64_t> _total_index_size;
    // TODO rowset Zonemap

    // written rows by add_block/add_row (not effected by segcompaction)
    std::atomic<int64_t> _raw_num_rows_written;

    struct Statistics {
        int64_t row_num;
        int64_t data_size;
        int64_t index_size;
        KeyBoundsPB key_bounds;
    };
    std::map<uint32_t, Statistics> _segid_statistics_map;
    std::mutex _segid_statistics_map_mutex;

    bool _is_pending = false;
    bool _already_built = false;

    SegcompactionWorker _segcompaction_worker;

    // ensure only one inflight segcompaction task for each rowset
    std::atomic<bool> _is_doing_segcompaction;
    // enforce compare-and-swap on _is_doing_segcompaction
    std::mutex _is_doing_segcompaction_lock;
    std::condition_variable _segcompacting_cond;

    std::atomic<int> _segcompaction_status;

    fmt::memory_buffer vlog_buffer;

    std::shared_ptr<MowContext> _mow_context;
};

} // namespace doris
