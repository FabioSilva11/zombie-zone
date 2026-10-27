#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <utility>
#include <vector>
#include "../world/blocks.h"
namespace zz {
struct TerrainEdit { int x,y,z; uint8_t block; };
namespace terrainwire {
constexpr uint8_t kVersion=3, kEdits=3, kAck=4;
constexpr size_t kMaxPacket=1400, kHeaderBytes=14, kEditBytes=7;
constexpr size_t kBatchSize=(kMaxPacket-kHeaderBytes)/kEditBytes;
// A arena PvP é finita; nunca aceitar coordenadas da ilha de outro modo.
inline bool validEdit(const TerrainEdit& e) {
    return e.x>=0 && e.x<128 && e.y>=0 && e.y<128 && e.z>=0 && e.z<128 && e.block<B_COUNT;
}
inline void put16(std::vector<uint8_t>& out,uint16_t v) {
    out.push_back(uint8_t(v)); out.push_back(uint8_t(v>>8));
}
inline void put32(std::vector<uint8_t>& out,uint32_t v) {
    put16(out,uint16_t(v)); put16(out,uint16_t(v>>16));
}
inline uint16_t get16(const uint8_t* p) { return uint16_t(p[0]) | uint16_t(p[1])<<8; }
inline uint32_t get32(const uint8_t* p) { return uint32_t(get16(p)) | uint32_t(get16(p+2))<<16; }
inline bool header(const uint8_t* data,size_t len,uint8_t type) {
    return data && len>=4 && len<=kMaxPacket && data[0]=='Z' && data[1]=='Z' &&
           data[2]==kVersion && data[3]==type;
}
class Journal {
public:
    void reset(uint32_t seed) { seed_=seed; edits_.clear(); }
    bool record(TerrainEdit edit) {
        if (!validEdit(edit) || edits_.size()>=std::numeric_limits<uint32_t>::max()) return false;
        edits_.push_back(edit); return true;
    }
    uint32_t revision() const { return static_cast<uint32_t>(edits_.size()); }
    std::vector<uint8_t> batch(uint32_t ack) const {
        if (ack>=edits_.size()) return {};
        const size_t count=std::min(kBatchSize,edits_.size()-ack);
        std::vector<uint8_t> out={'Z','Z',kVersion,kEdits};
        out.reserve(kHeaderBytes+count*kEditBytes);
        put32(out,seed_); put32(out,ack+1); put16(out,static_cast<uint16_t>(count));
        for(size_t i=ack;i<ack+count;++i) {
            const auto& e=edits_[i];
            put16(out,static_cast<uint16_t>(e.x)); put16(out,static_cast<uint16_t>(e.y));
            put16(out,static_cast<uint16_t>(e.z)); out.push_back(e.block);
        }
        return out;
    }
private:
    uint32_t seed_=0;
    std::vector<TerrainEdit> edits_;
};
class Receiver {
public:
    void reset(uint32_t seed) { seed_=seed; revision_=0; pending_.clear(); }
    bool accept(const uint8_t* data,size_t len) {
        if (!header(data,len,kEdits) || len<kHeaderBytes || get32(data+4)!=seed_) return false;
        const uint32_t first=get32(data+8);
        const uint16_t count=get16(data+12);
        const uint64_t last=uint64_t(first)+count-1;
        if (!first || !count || count>kBatchSize || len!=kHeaderBytes+count*kEditBytes ||
            last>std::numeric_limits<uint32_t>::max() || uint64_t(first)>uint64_t(revision_)+1) return false;
        // Valida o lote INTEIRO antes de enfileirar ou confirmar qualquer edição.
        for(size_t i=0;i<count;++i) {
            const uint8_t* p=data+kHeaderBytes+i*kEditBytes;
            if (!validEdit({get16(p),get16(p+2),get16(p+4),p[6]})) return false;
        }
        for(size_t i=0;i<count;++i) {
            if (uint64_t(first)+i<=revision_) continue;
            const uint8_t* p=data+kHeaderBytes+i*kEditBytes;
            pending_.push_back({get16(p),get16(p+2),get16(p+4),p[6]});
        }
        revision_=std::max(revision_,static_cast<uint32_t>(last));
        return true;
    }
    uint32_t revision() const { return revision_; }
    std::vector<TerrainEdit> take() { std::vector<TerrainEdit> out; out.swap(pending_); return out; }
private:
    uint32_t seed_=0,revision_=0;
    std::vector<TerrainEdit> pending_;
};
inline std::vector<uint8_t> ackPacket(uint32_t seed,int id,uint32_t revision) {
    if(id<=0 || id>=8) return {};
    std::vector<uint8_t> out={'Z','Z',kVersion,kAck};
    put32(out,seed); out.push_back(static_cast<uint8_t>(id)); put32(out,revision); return out;
}
inline bool readAck(const uint8_t* data,size_t len,uint32_t seed,int& id,uint32_t& revision) {
    if (!header(data,len,kAck) || len!=13 || get32(data+4)!=seed || data[8]==0 || data[8]>=8) return false;
    id=data[8]; revision=get32(data+9); return true;
}
inline bool advanceAck(uint32_t& accepted,uint32_t revision,uint32_t sentThrough) {
    if(revision>sentThrough) return false;
    accepted=std::max(accepted,revision); return true;
}
}
}
